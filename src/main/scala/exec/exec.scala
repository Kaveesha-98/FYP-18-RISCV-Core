package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import hardfloat._

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._

class pullToPipeline extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val src1        = Input(UInt(64.W))
  val src2        = Input(UInt(64.W))
  val writeData   = Input(UInt(64.W))
  val instruction = Input(UInt(32.W))
}

class pushToMemory extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val memAddress  = Output(UInt(64.W))
  val writeData   = Output(UInt(64.W))
  val instruction = Output(UInt(32.W))
  //! Debug
  val insState = Output(UInt(3.W))
  // val fwrite      = Output(Bool())
}

/* class pushExecResultToRob extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val execResult  = Output(UInt(64.W))
} */

class exec extends Module {

  val toMemory  = IO(new pushToMemory)
  /* val memReqBuffer = RegInit((new Bundle{
    val waiting     = Bool()
    val robAddr     = UInt(robAddrWidth.W)
    val memAddress  = UInt(64.W)
    val writeData   = UInt(64.W)
    val instruction = UInt(32.W)
  }).Lit(
    _.waiting     -> false.B,
    _.robAddr     -> 0.U,
    _.memAddress  -> 0.U,
    _.writeData   -> 0.U,
    _.instruction -> 0.U
  ))
  toMemory.ready        := memReqBuffer.waiting
  toMemory.robAddr      := memReqBuffer.robAddr
  toMemory.memAddress   := memReqBuffer.memAddress
  toMemory.writeData    := memReqBuffer.writeData
  toMemory.instruction  := memReqBuffer.instruction */

  val toRob     = IO(new pushExecResultToRob)
  val robPushBuffer = RegInit((new Bundle{
    val waiting = Bool()
    val robAddr = UInt(robAddrWidth.W)
    val execResult = UInt(64.W)
    val writeData   = UInt(64.W)
    val instruction = UInt(32.W)
    val mcause = UInt(64.W)
    val execptionOccured = Bool()
	val eflags = UInt(5.W)
	// val fwrite = Bool()
  }).Lit(
    _.waiting -> false.B,
    _.robAddr -> 0.U,
    _.execResult -> 0.U,
    _.writeData   -> 0.U,
    _.instruction -> 0.U,
    _.execptionOccured -> false.B
  ))
  val passToMem = RegInit(false.B)
  toRob.ready := robPushBuffer.waiting && (robPushBuffer.instruction(6, 2) =/= "b00000".U && robPushBuffer.instruction(6, 2) =/= "b01011".U)
  toRob.robAddr := robPushBuffer.robAddr
  toRob.execResult := robPushBuffer.execResult
  toRob.eflags := robPushBuffer.eflags
  // toRob.fwrite := robPushBuffer.fwrite

  toMemory.ready        := passToMem
  toMemory.robAddr      := robPushBuffer.robAddr
  toMemory.memAddress   := robPushBuffer.execResult
  toMemory.writeData    := robPushBuffer.writeData
  toMemory.instruction := robPushBuffer.instruction
  // toMemory.fwrite := robPushBuffer.fwrite
  //! Debug signals
  val insState = WireInit(0.U(3.W))
  toMemory.insState := insState
  val fromIssue = IO(new pullToPipeline)

  val bufferedEntries = Seq.fill(2)((RegInit((new Bundle{
    val free = Bool()
		val robAddr 	= UInt(robAddrWidth.W)
    val src1 = UInt(64.W)
		val src2 = UInt(64.W)
		val writeData 	= UInt(64.W)
    val instruction = UInt(32.W)
	}).Lit(
		_.free 	      -> true.B,
    _.robAddr     -> 0.U,
		_.src1	      -> 0.U,
		_.src2 		    -> 0.U,
    _.writeData   -> 0.U,
    _.instruction -> 0.U
	))))

  //fanout reduce
  val fpubuf = ((RegInit((new Bundle{
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val instruction = UInt(32.W)
	}).Lit(
		_.src1	      -> 0.U,
		_.src2 		    -> 0.U,
    _.instruction -> 0.U
	))))
  //ends here


  // as long as the free buffer is available the module can accept new requests
  fromIssue.ready := bufferedEntries(1).free



  // bufferedEntries(0) will be current executing entry
  val nextExecutingEntry = Wire(bufferedEntries(0).cloneType)

  when(!bufferedEntries(1).free) {
    nextExecutingEntry := bufferedEntries(1)
  }.otherwise {
    nextExecutingEntry.free         := !(fromIssue.fired && fromIssue.ready)
    nextExecutingEntry.robAddr      := fromIssue.robAddr
    nextExecutingEntry.src1         := fromIssue.src1
    nextExecutingEntry.src2         := fromIssue.src2
    nextExecutingEntry.writeData    := fromIssue.writeData
    //nextExecutingEntry.instruction  := fromIssue.instruction 
	nextExecutingEntry.instruction  := Mux(fromIssue.instruction(6,2) === BitPat("b0?001"), Cat(fromIssue.instruction(31,3),0.U(1.W),fromIssue.instruction(1,0)), fromIssue.instruction)
	
	
  }

  val FPU = Module(new fpu(8, 24))
  val multiply = Module(new multiplier)
  val divide = Module(new divider)
  def isMExtenMul(x: UInt) = (x(6, 2) === BitPat("b011?0")) && x(25).asBool
  Seq(multiply, divide).foreach(m => {
    m.inputs.valid := isMExtenMul(bufferedEntries(0).instruction) && m.turnOn(bufferedEntries(0).instruction) && !bufferedEntries(0).free
    m.inputs.bits.src1 := bufferedEntries(0).src1
    m.inputs.bits.src2 := bufferedEntries(0).src2
    m.inputs.bits.mOp := Cat(bufferedEntries(0).instruction(3), bufferedEntries(0).instruction(14, 12))
  })
  
  /**
    * Processing the currently scheduled entry. To process the entry, the result buffers
    * (to rob and/or memory) needs to free or the pending entry is accepted in sampling 
    * cycle.
    */
  /* val updateCurrentEntry = (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000") && (!robPushBuffer.waiting || toRob.fired)) ||
    (bufferedEntries(0).instruction(6, 2) === BitPat("b0?000") && (!memReqBuffer.waiting || toMemory.fired)) */
  val updateCurrentEntry = (bufferedEntries(0).free ||
    (
      (!robPushBuffer.waiting || toRob.fired) && 
      (!passToMem || toMemory.fired) && 
      (!isMExtenMul(bufferedEntries(0).instruction) || Seq(multiply, divide).map(_.output.valid).reduce(_ || _)) &&
	  (!(Cat(bufferedEntries(0).instruction(31,27),bufferedEntries(0).instruction(6,2))===BitPat("b0?01110100")) || FPU.io.valid_div || FPU.io.valid_sqrt)
    ))

  Seq(multiply, divide).map(_.output.ready).foreach(_ := updateCurrentEntry)

  val freshInput = RegInit(false.B)

  when(updateCurrentEntry) {
    bufferedEntries(0) := nextExecutingEntry
    fpubuf.src1 := nextExecutingEntry.src1
    fpubuf.src2 := nextExecutingEntry.src2
    fpubuf.instruction := nextExecutingEntry.instruction
  }

  // when current entry can't be processed, the next one is buffered
  when(!updateCurrentEntry && bufferedEntries(1).free) {
    bufferedEntries(1).free         := !(fromIssue.fired && fromIssue.ready)
    bufferedEntries(1).robAddr      := fromIssue.robAddr
    bufferedEntries(1).src1         := fromIssue.src1
    bufferedEntries(1).src2         := fromIssue.src2
    bufferedEntries(1).writeData    := fromIssue.writeData
    bufferedEntries(1).instruction  := Mux(fromIssue.instruction(6,2) === BitPat("b0?001"), Cat(fromIssue.instruction(31,3),0.U(1.W),fromIssue.instruction(1,0)), fromIssue.instruction)
  }

  // buffered entry is sent to processing
  when(updateCurrentEntry && !bufferedEntries(1).free) {
    bufferedEntries(1).free := true.B
  }

  val execResult = {
    def result32bit(res: UInt) =
      Cat(Fill(32, res(31)), res(31, 0))

    val instruction = bufferedEntries(0).instruction
    val src1 = bufferedEntries(0).src1
    val src2 = bufferedEntries(0).src2
    val addSub64 = VecInit(src1 + src2, src1 - src2)

    val result64M = VecInit.tabulate(2)(i => i match {
        case 0 => multiply.output.bits
        case 1 => divide.output.bits
      })(instruction(14))

      val src1W = src1(31,0)
      val src2W = src2(31,0)
      val result32MW = VecInit.tabulate(2)(i => i match {
        case 0 => multiply.output.bits
        case 1 => divide.output.bits
      })(instruction(14))
      val result32M = Cat(Fill(32, result32MW(31)), result32MW)

    /**
        * 64 bit operations, indexed with funct3, op-imm, op
        */
    val arithmetic64 = VecInit.tabulate(8)(i => i match {
      case 0 => Mux(Cat(instruction(30), instruction(5)) === "b11".U, src1 - src2, src1 + src2)
      case 1 => (src1 << src2(5, 0))
      case 2 => (src1.asSInt < src2.asSInt).asUInt
      case 3 => (src1 < src2).asUInt
      case 4 => (src1 ^ src2)
      case 5 => Mux(instruction(30).asBool, (src1.asSInt >> src2(5, 0)).asUInt, (src1 >> src2(5, 0)))
      case 6 => (src1 | src2)
      case 7 => (src1 & src2)
    })(instruction(14, 12))

    /**
      * 32 bit operations, indexed with funct3, op-imm-32, op-32
      */
    val arithmetic32 = VecInit.tabulate(4)(i => i match {
      case 0 => Mux(Cat(instruction(30), instruction(5)) === "b11".U, result32bit(src1 - src2), result32bit(src1 + src2)) // add & sub
      case 1 => (result32bit(src1 << src2(4, 0))) // sll\iw
      case 2 => (result32bit(src1 << src2(4, 0))) // filler
      case 3 => Mux(instruction(30).asBool, result32bit((src1(31, 0).asSInt >> src2(4, 0)).asUInt), result32bit(src1(31, 0) >> src2(4, 0))) // sra\l\iw
    })(Cat(instruction(14), instruction(12)))

    /**
      * Taken from register mapping in the instruction listing risc-spec
      */
    // possible place for resource optimization
    VecInit.tabulate(7)(i => i match {
      case 0 => (src1 + src2) // address calculation for memory access
      //case 1 => (src2 + 4.U) // jal link address
      case 1 => Mux(instruction(6).asBool, (src2 + 4.U), (src1 + src2)) // jal link address or flw,fsw mem address
      case 2 => (src1 + src2) //(63, 0) // filler
      case 3 => Mux(instruction(6).asBool, (src1 + src2), src1) //(63, 0) jalr link address
      case 4 => Mux(instruction === BitPat("b0000001??????????????????0110011"), result64M, arithmetic64) // (63, 0) op-imm, op
      case 5 => (src2 + Mux(instruction(5).asBool, 0.U, src1)) // (63, 0) // lui and auipc
      case 6 => Mux(instruction === BitPat("b0000001??????????????????0111011"), result32M, arithmetic32) // op-32, op-imm-32
    })(instruction(4, 2))
  }
  
  FPU.io.a := fpubuf.src1
  FPU.io.b := fpubuf.src2
  FPU.io.inst := fpubuf.instruction
  FPU.io.toRobReady := toRob.ready
  toMemory.insState := FPU.io.insState
  FPU.io.inValid := (Cat(bufferedEntries(0).instruction(31,27),bufferedEntries(0).instruction(6,2))===BitPat("b0?01110100")) && !bufferedEntries(0).free
  

  val memAddress = bufferedEntries(0).src1 + bufferedEntries(0).src2
  val funct5List = List("b10000".U,"b10001".U,"b10010".U,"b10011".U,"b10100".U) //F-OP and 3 operand
                                                                                        
  val fpuOp = funct5List.map(funct5List => bufferedEntries(0).instruction(6,2) === funct5List).reduce(_ || _)

  when(updateCurrentEntry && ((!robPushBuffer.waiting || toRob.fired) && (!passToMem || toMemory.fired))) {
    robPushBuffer.execResult := Mux(fpuOp, FPU.io.result, execResult)
    robPushBuffer.robAddr := bufferedEntries(0).robAddr
    robPushBuffer.writeData := bufferedEntries(0).writeData
    robPushBuffer.instruction := bufferedEntries(0).instruction
    robPushBuffer.execptionOccured := bufferedEntries(0).instruction(6, 0) === "h73".U
    robPushBuffer.eflags := FPU.io.ef 
    // robPushBuffer.fwrite := FPU.io.fwrite
  }

  when(robPushBuffer.waiting) {
    // condition to deassert waiting(if true it has keep on waiting)
    robPushBuffer.waiting := (!toRob.fired || (!bufferedEntries(0).free && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b00000") && bufferedEntries(0).instruction(6, 2) =/= BitPat("b01011")) && (!passToMem || toMemory.fired)))// && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b00011"))
  }.otherwise {
    // asserting waiting
    robPushBuffer.waiting := !bufferedEntries(0).free && ((bufferedEntries(0).instruction(6, 2) =/= BitPat("b00000") && bufferedEntries(0).instruction(6, 2) =/= BitPat("b01011")) && updateCurrentEntry)// && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b00011"))
  }

  when(passToMem) {
    passToMem := (!toMemory.fired || (!bufferedEntries(0).free && ((bufferedEntries(0).instruction(6, 2) === BitPat("b0?0??")) && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b00011")) ) && (!robPushBuffer.waiting || toRob.fired))) 
  }.otherwise {
    passToMem := !bufferedEntries(0).free && (((bufferedEntries(0).instruction(6, 2) === BitPat("b0?0??")) && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b00011")) ) && updateCurrentEntry) 
  }
  toRob.execptionOccured := robPushBuffer.execptionOccured
  toRob.mcause := Mux(robPushBuffer.instruction(31).asBool, (1.U(64.W) << 63) | 7.U(64.W),Mux(robPushBuffer.instruction === 115.U, 11.U, 3.U))
}
