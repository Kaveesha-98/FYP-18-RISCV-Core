package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

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
}

class pushExecResultToRob extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val execResult  = Output(UInt(64.W))
}

class exec extends Module {

  val toMemory  = IO(new pushToMemory)
  val memReqBuffer = RegInit((new Bundle{
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
  toMemory.instruction  := memReqBuffer.instruction

  val toRob     = IO(new pushExecResultToRob)
  val robPushBuffer = RegInit((new Bundle{
    val waiting = Bool()
    val robAddr = UInt(robAddrWidth.W)
    val execResult = UInt(64.W)
  }).Lit(
    _.waiting -> false.B,
    _.robAddr -> 0.U,
    _.execResult -> 0.U
  ))
  toRob.ready := robPushBuffer.waiting
  toRob.robAddr := robPushBuffer.robAddr
  toRob.execResult := robPushBuffer.execResult

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
    nextExecutingEntry.instruction  := fromIssue.instruction 
  }
  
  /**
    * Processing the currently scheduled entry. To process the entry, the result buffers
    * (to rob and/or memory) needs to free or the pending entry is accepted in sampling 
    * cycle.
    */
  /* val updateCurrentEntry = (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000") && (!robPushBuffer.waiting || toRob.fired)) ||
    (bufferedEntries(0).instruction(6, 2) === BitPat("b0?000") && (!memReqBuffer.waiting || toMemory.fired)) */
  val updateCurrentEntry = (bufferedEntries(0).free ||
    (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000") && (!robPushBuffer.waiting || toRob.fired)) ||
    (bufferedEntries(0).instruction(6, 2) === BitPat("b0?000") && (!memReqBuffer.waiting || toMemory.fired)))

  when(updateCurrentEntry) {
    bufferedEntries(0) := nextExecutingEntry
  }

  // when current entry can't be processed, the next one is buffered
  when(!updateCurrentEntry && bufferedEntries(1).free) {
    bufferedEntries(1).free         := !(fromIssue.fired && fromIssue.ready)
    bufferedEntries(1).robAddr      := fromIssue.robAddr
    bufferedEntries(1).src1         := fromIssue.src1
    bufferedEntries(1).src2         := fromIssue.src2
    bufferedEntries(1).writeData    := fromIssue.writeData
    bufferedEntries(1).instruction  := fromIssue.instruction 
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
      case 1 => (src1 + src2) // jal link address
      case 2 => (src1 + src2) //(63, 0) // filler
      case 3 => (src1 + src2) //(63, 0) jalr link address
      case 4 => arithmetic64 // (63, 0) op-imm, op
      case 5 => (src2 + Mux(instruction(5).asBool, 0.U, src1)) // (63, 0) // lui and auipc
      case 6 => arithmetic32 // op-32, op-imm-32
    })(instruction(4, 2))
  }

  val memAddress = bufferedEntries(0).src1 + bufferedEntries(0).src2

  when(updateCurrentEntry) {
    when(bufferedEntries(0).instruction(6, 2) === BitPat("b0?000")) {
      memReqBuffer.robAddr := bufferedEntries(0).robAddr
      memReqBuffer.memAddress := memAddress
      memReqBuffer.writeData := bufferedEntries(0).writeData
      memReqBuffer.instruction := bufferedEntries(0).instruction
    }.otherwise {
      robPushBuffer.execResult := execResult
      robPushBuffer.robAddr := bufferedEntries(0).robAddr
    }
  }

  when(robPushBuffer.waiting) {
    // condition to deassert waiting(if true it has keep on waiting)
    robPushBuffer.waiting := !toRob.fired || (!bufferedEntries(0).free && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000")))
  }.otherwise {
    // asserting waiting
    robPushBuffer.waiting := !bufferedEntries(0).free && (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000"))
  }

  when(memReqBuffer.waiting) {
    memReqBuffer.waiting := !toMemory.fired || bufferedEntries(0).free || (bufferedEntries(0).instruction(6, 2) =/= BitPat("b0?000"))
  }.otherwise {
    memReqBuffer.waiting := !bufferedEntries(0).free && (bufferedEntries(0).instruction(6, 2) === BitPat("b0?000"))
  }
}

object exec extends App {
  (new stage.ChiselStage).emitVerilog(new exec)
}