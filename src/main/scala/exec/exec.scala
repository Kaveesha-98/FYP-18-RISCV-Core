package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.ports

class pullToPipeline extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val src1        = Input(UInt(64.W))
  val src2        = Input(UInt(64.W))
  val writeData   = Input(UInt(64.W))
  val instruction = Input(UInt(32.W))
  val meta        = Input(new ports.meta)
}

class execRequest extends Bundle {
  val fwdAddr = UInt(robAddrWidth.W)
  val src1 = UInt(XLEN.W)
  val src2 = UInt(XLEN.W)
  val writeData = UInt(XLEN.W)
  val instruction = UInt(ILEN.W)
}

class pushToMemory extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val memAddress  = Output(UInt(64.W))
  val writeData   = Output(UInt(64.W))
  val instruction = Output(UInt(32.W))
}

class execResult extends Bundle {
  val fwdAddr = UInt(robAddrWidth.W)
  val result = UInt(XLEN.W)
  val writeData = UInt(XLEN.W)
  val instruction = UInt(ILEN.W)
  val toFwd = Bool()
  val dataOnly = Bool()
}

class toFwdFrmExec extends Bundle {
  val fwdAddr = UInt(robAddrWidth.W)
  val result = UInt(XLEN.W)
}

/**
  * Functionality - Executes a calculation relevant to a given 
  * instruction
  * 
  * All instructions will be accpeted through fromIssue interface.
  * All instructions accepted must be in the correct program order.
  * Some logic outside this module will be partly responsible to
  * assert that all instructions accpeted through fromIssue belongs
  * to the correct program order.
  * 
  * Address calculation will be carried out for memory access 
  * instructions.
  * 
  * All instructions will be passed to the memAccess unit. Instructions
  * that require multiple cycles to execute such as multiply and divide
  * may be passed to memAccess unit before they have been finished 
  * executing and the results can be given later
  * 
  * There are three registers to manage the instruction flow
  * 1. servicingRequest: This will drive the arithmetic hardware for
  *   executing the instruction
  * 2. stalledRequest: When toMemory and toFwd are stalled, this is used
  *   to manage the flow
  * 3. servicedRequest: After the request is serviced, it will be stored
  *   until it is moved to the next stage of the pipeline
  */

class exec extends Module {

  val toMemory  = IO(ComposableIO(Output(new execResult)))
  val toFwd     = IO(ComposableIO(Output(new toFwdFrmExec))) // TODO: remove
  val fromIssue = IO(ComposableIO(Input(new execRequest)))
  val branchResults = IO(Valid(UInt(XLEN.W)))

  // This register will drive the inputs for the hardware arithmetic
  // execution units.
  // There are two sources for this register
  // 1. fromIssue: when the pipeline is not stalled
  // 2. stalledRequest: when recovering from a pipeline stall
  // This register is updated whenever:
  // 1. This register is not occupied with a request
  // 2. Occupying instruction has been serviced and sent to servicedRequest
  //    register
  // Meaning of 'executed' according to the type of instruction occupying
  // the register
  // -> RV64M: sources has been sent to the correspoding execution unit
  // -> other: Instruction just occupyied the register in this cycle
  val servicingRequest = RegInit(Valid( new Bundle {
    val request = fromIssue.bits.cloneType
    val executed = Bool()
  }).Lit(_.valid -> false.B))

  // This will be used when pipeline stalls happen or servicing of
  // the request cannot happen in this cycle. 
  // Only one source for this register (fromIssue interface) and
  // updated when fromIssue.fired and the instruction occupying the
  // servicingRequest cannot be sent to servicedRequest register.
  val stalledRequest = RegInit(Valid(fromIssue.bits.cloneType).Lit(_.valid -> false.B))

  // This will be used to drive toMemory interface. There are 3
  // sources when updating the register
  // 1. servicingRequest: default option
  // 2. multiply.output: result for integer multiply instructions,
  //    when the result on multiply.output belongs to the same
  //    instruction as in servicingRequest, the updated value will
  //    be dependent on multiply.output and servicingRequest
  // 3. divide.output: result for integer divide instructions,
  //    when the result on divide.output belongs to the same
  //    instruction as in servicingRequest, the updated value will
  //    be dependent on divide.output and servicingRequest
  // To update the register either 
  // 1. The register is not occupied by a instruction (or DataOnly entry)
  // 2. Occupied instruction is issued out toMemory
  val servicedRequest = RegInit(Valid(toMemory.bits.cloneType).Lit(_.valid -> false.B))

  // These modules perform integer multiplication and division. The
  // inputs are driven by servicingRequest register through a ready
  // valid interface. For each instruction that requires integer 
  // multiplication or division, the input interface must only fire
  // once from the corresponding module.
  // 'divide' only supports one instruction at a time.
  // 'multiply' should be supported and should support more than 
  // instruction at a time.
  val multiply = Module(new multiplier)
  val divide = Module(new divider)

  // Stall detected in next module in pipeline
  val toMemoryInterfaceStalled = toMemory.ready && !toMemory.fired

  // Can we move the instruction occupying the register servicingRequest
  // be sent to servicedRequest
  val servicingRequestReadyForNextStage = Wire(Bool())

  // Any instruction other than RV64M we can service in a single cycle
  if (rv64mIsPipelined) {
    // We only give the data to corresponding unit for RV64M instructions
    servicingRequestReadyForNextStage := servicingRequest.valid && servicingRequest.bits.executed && 
      Mux()
  }

  // States for a instruction occupying servicingRequest,
  // for instructions that can be serviced in same cycle.
  // 1. new (the first cycle instruction occupies the register)
  // 2. stalled

  //↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
  //|||||||||||||||||||||| new design ||||||||||||||||||||
  //======================================================

  // executingRequest drives the request into executing logic
  // bufferedRequest is used to handle stalling situations 
  val bufferedRequest = RegInit(validBundle(new execRequest) Lit(_.valid -> false.B))

  val executingRequest = RegInit(validBundle(new Bundle {
    val request = new execRequest
    // TODO: Explain this signal
    val onStall = Bool()
  }) Lit(_.valid -> false.B))
  
  // Stores the instruction (and/or result) 
  val executedRequest = RegInit(validBundle(new Bundle {
    val result = new execResult
    val pushToFwd, pushToMem = Bool()
  }) Lit(_.valid -> false.B))

  toMemory.ready := executedRequest.valid && executedRequest.bits.pushToMem
  toFwd.ready := executedRequest.valid && executedRequest.bits.pushToFwd

  fromIssue.ready := !bufferedRequest.valid

  branchResults.valid := executingRequest.valid && !executingRequest.bits.onStall && isBranch(executingRequest.bits.request.instruction)

  Seq(multiply, divide).foreach(m => {
    m.inputs.valid := false.B
    when(executingRequest.valid && !executingRequest.bits.onStall) {
      m.inputs.valid := isMExtenMul(executingRequest.bits.request.instruction) && m.turnOn(executingRequest.bits.request.instruction)
    }
    m.inputs.bits.src1 := executingRequest.bits.request.src1
    m.inputs.bits.src2 := executingRequest.bits.request.src2
    m.inputs.bits.mOp := m.getmOp(executingRequest.bits.request.instruction)
  }) 

  // Currently only M-extension instructions can't be serviced in a single cycle.
  // To execute them, we have to wait for the relevant execution unit to be ready.
  // Then we can ask the relevant unit to perform them and let the pipeline move
  // on to other instructions. 'Service' of a M-extension mean that a request is
  // fired to the relevant execution unit.
  val reqServicedInThisCycle = 
    executingRequest.valid && !Seq(multiply, divide).map(m => m.inputs.valid && !m.inputs.ready).reduce(_ || _) 

  val executedInstructionStalled = 
    Seq(toFwd, toMemory).map(i => i.fired || !i.ready).reduce(_ && _)

  val nextResult = Wire(executedRequest.bits.result.cloneType)
  nextResult.fwdAddr := executingRequest.bits.request.fwdAddr
  nextResult.instruction := executingRequest.bits.request.instruction
  nextResult.writeData := executingRequest.bits.request.writeData
  nextResult.result := {
    def result32bit(res: UInt) =
      Cat(Fill(32, res(31)), res(31, 0))

    val instruction = executingRequest.bits.request.instruction
    val src1 = executingRequest.bits.request.src1
    val src2 = executingRequest.bits.request.src2
    val addSub64 = VecInit(src1 + src2, src1 - src2)

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
      case 1 => (src2 + 4.U) // jal link address
      case 2 => (src1 + src2) //(63, 0) // filler
      case 3 => Mux(instruction(6).asBool, (src1 + src2), src1) //(63, 0) jalr link address
      case 4 => arithmetic64 // (63, 0) op-imm, op
      case 5 => (src2 + Mux(instruction(5).asBool, 0.U, src1)) // (63, 0) // lui and auipc
      case 6 => arithmetic32 // op-32, op-imm-32
    })(instruction(4, 2))
  }

}

object exec extends App {
  (new stage.ChiselStage).emitVerilog(new exec)
}