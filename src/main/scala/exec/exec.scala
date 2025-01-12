package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.ports
import pipeline.decode.constants.opcode5MSBs

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
  val pc = UInt(XLEN.W)
  val meta        = new ports.meta
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
  val meta        = new ports.meta
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
  // val toFwd     = IO(ComposableIO(Output(new toFwdFrmExec))) // TODO: remove
  val fromIssue = IO(ComposableIO(Input(new execRequest)))
  val branchResults = IO(Output(Valid(UInt(XLEN.W))))
  // Below is used to forward the result of the instruction currently
  // in servicingRequest to Issue stage.
  val quickForward = IO(Output(Valid(new Bundle {
    val fwdAddr = UInt(fwdAddrWidth.W)
    val data = UInt(XLEN.W)
  })))

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
    // All instructions except RV64M instructions are ready in the same
    // cycle the instruction arrives on 'servicingRequest'
    // For RV64M, the instruction must give the sources to the corresponding
    // executing unit first.
    // Instruction in servicingRequest has higher priority over the data
    // in multiply.output or divide.output.
    // Only one of servicingRequest.bits.executed, multiply.inputs.fire or 
    // divide.inputs.fire can be high at a time
    // Prority is not performance based, just random thought ¯\_(ツ)_/¯
    servicingRequestReadyForNextStage := servicingRequest.valid && (Mux(!RV64Minstruction(servicingRequest.bits.request.instruction), true.B,
      servicingRequest.bits.executed || (multiply.inputs.fire || divide.inputs.fire)) || servicingRequest.bits.request.meta.exception)

    // multiply.output has a higher prority than divide.output, unless
    // the data in divide.output corresponds to the data in servicingRequest
    when (servicingRequestReadyForNextStage) {
      // When the request in servicingRequest corresponds to the data in
      // the execution unit, we also take the data in the execution unit
      // in the same cycle
      multiply.output.ready := 
        !toMemoryInterfaceStalled && isIntegerMultiply(servicingRequest.bits.request.instruction) &&
        (servicingRequest.bits.request.fwdAddr === multiply.output.bits.fwdAddr) && !servicingRequest.bits.request.meta.exception
    }.otherwise {
      multiply.output.ready := !toMemoryInterfaceStalled
    }
    when (servicingRequestReadyForNextStage) {
       // When the request in servicingRequest corresponds to the data in
      // the execution unit, we also take the data in the execution unit
      // in the same cycle
      divide.output.ready := 
        !toMemoryInterfaceStalled && isIntegerDivide(servicingRequest.bits.request.instruction)
        (servicingRequest.bits.request.fwdAddr === divide.output.bits.fwdAddr) && !servicingRequest.bits.request.meta.exception
    }.otherwise {
      divide.output.ready := !toMemoryInterfaceStalled && !multiply.output.valid
    }
  }

  abstract class sameCycleArithmetic extends Module {
    val inputs = IO(Input(new Bundle {
      val src1 = UInt(XLEN.W)
      val src2 = UInt(XLEN.W)
    }))

    val output = IO(Output(UInt(XLEN.W)))

    def operation(x: UInt, y: UInt): UInt

    output := operation(inputs.src1, inputs.src2)
  }

  val addition64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x + y})
  val shiftLeft64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x << y(5,0)})
  val setLessThanUnsigned63bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = (x < y).asUInt})
  val xor64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x ^ y})
  val shiftRightLogic64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x >> y(5,0)})
  val shiftRightArithmetic64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = (x.asSInt >> y(5,0)).asUInt})
  val or64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x | y})
  val and64bit = Module(new sameCycleArithmetic {def operation(x: UInt, y: UInt): UInt = x & y})

  // setting up inputs for addition
  when ((servicingRequest.bits.request.instruction) === opcode5MSBs.condJump.U) {
    // Have to calculate the jumping instruction when branch condition true
    addition64bit.inputs.src1 := servicingRequest.bits.request.pc
    addition64bit.inputs.src2 := getImmediateTypeB(servicingRequest.bits.request.instruction)
  }.otherwise {
    // For all other requests, if addition is required, the sources must be
    // in src1 and src2
    addition64bit.inputs.src1 := servicingRequest.bits.request.src1
    addition64bit.inputs.src2 := Mux(isSubstraction(servicingRequest.bits.request.instruction), ~(servicingRequest.bits.request.src2) + 1.U(XLEN.W), servicingRequest.bits.request.src2)
  }

  // setting up inputs for shift left logic
  // only used for sll, sllw, slli, sllwi
  // source 1 will always be src1
  shiftLeft64bit.inputs.src1 := servicingRequest.bits.request.src1
  // Only 5 LSBs considered for 32 bit ops
  // Only 6 LSBs considered for 64 bit ops
  shiftLeft64bit.inputs.src2 := Cat(
    0.U((XLEN-6).W), 
    Mux(is32Arithmetic(servicingRequest.bits.request.instruction), 0.U(1.W), servicingRequest.bits.request.src2(5).asUInt),
    servicingRequest.bits.request.src2(4,0)
  )

  // setting inputs for set less than
  // we only look at 63 LSBs as unsigned values,
  // then use that result to calculate for full 64 bits later
  setLessThanUnsigned63bit.inputs.src1 := servicingRequest.bits.request.src1(62,0)
  setLessThanUnsigned63bit.inputs.src2 := servicingRequest.bits.request.src2(62,0)

  // setting inputs for xor
  xor64bit.inputs.src1 := servicingRequest.bits.request.src1
  xor64bit.inputs.src2 := servicingRequest.bits.request.src2

  // settingup inputs for shift right logic
  // sign extending for 32 calculations, we dont have to change anything later
  shiftRightLogic64bit.inputs.src1 := Cat(
    Mux(is32Arithmetic(servicingRequest.bits.request.instruction), 0.U(32.W), servicingRequest.bits.request.src1(63,32)),
    servicingRequest.bits.request.src1(31,0)
  )
  shiftRightLogic64bit.inputs.src2 := Cat(
    0.U((XLEN-6).W),
    Mux(is32Arithmetic(servicingRequest.bits.request.instruction), 0.U(1.W), servicingRequest.bits.request.src2(5).asUInt),
    servicingRequest.bits.request.src2(4,0)
  )

  // settingup inputs for shift right arithmetic
  // sign extending for 32 calculations, we dont have to change anything later
  shiftRightArithmetic64bit.inputs.src1 := Cat(
    Mux(is32Arithmetic(servicingRequest.bits.request.instruction), Fill(32, servicingRequest.bits.request.src1(31)) , servicingRequest.bits.request.src1(63,32)),
    servicingRequest.bits.request.src1(31,0)
  )
  shiftRightArithmetic64bit.inputs.src2 := Cat(
    0.U((XLEN-6).W),
    Mux(is32Arithmetic(servicingRequest.bits.request.instruction), 0.U(1.W), servicingRequest.bits.request.src2(5).asUInt),
    servicingRequest.bits.request.src2(4,0)
  )

  // setting up inputs for or
  or64bit.inputs.src1 := servicingRequest.bits.request.src1
  or64bit.inputs.src2 := servicingRequest.bits.request.src2

  // setting up inputs for and
  when (isSystem(servicingRequest.bits.request.instruction)) {
    and64bit.inputs.src1 := ~(servicingRequest.bits.request.src1)
  }.otherwise {
    and64bit.inputs.src1 := servicingRequest.bits.request.src1
  }
  and64bit.inputs.src2 := servicingRequest.bits.request.src2

  // branch execution
  branchResults.valid := isBranch(servicingRequest.bits.request.instruction) && !servicingRequest.bits.executed && servicingRequest.valid && !servicingRequest.bits.request.meta.exception
  val slt = Mux(
    servicingRequest.bits.request.src1(63) ^ servicingRequest.bits.request.src1(63),
    servicingRequest.bits.request.src1(63), setLessThanUnsigned63bit.output(0)
  )
  val sltu = Mux(
    servicingRequest.bits.request.src1(63) ^ servicingRequest.bits.request.src1(63),
    servicingRequest.bits.request.src2(63), setLessThanUnsigned63bit.output(0)
  )
  val equals = !xor64bit.output.orR
  val selectSourceCompare = Mux(
    servicingRequest.bits.request.instruction(14),
    Mux(servicingRequest.bits.request.instruction(13), sltu, slt),
    equals
  )
  val branchTaken = 
    Mux(servicingRequest.bits.request.instruction(12), !selectSourceCompare, selectSourceCompare) || isUnconditionalJump(servicingRequest.bits.request.instruction)
  // if this is an instruction with an exception, next instruction will be mtvec and will be set when the instruction retires
  val nextPCAfterServicingRequest = Mux(isBranch(servicingRequest.bits.request.instruction) && branchTaken, addition64bit.output, servicingRequest.bits.request.pc + 4.U(XLEN.W))

  branchResults.bits := nextPCAfterServicingRequest
  
  def take32signExtendedWhenNeeded(result64bit: UInt) = 
    Cat(Mux(is32Arithmetic(servicingRequest.bits.request.instruction), Fill(32, result64bit(31)), result64bit(63,32)), result64bit(31,0))
  
  val sameCycleArithmeticResult = VecInit.tabulate(8)(_ match {
    case 0 => take32signExtendedWhenNeeded(addition64bit.output)
    case 1 => take32signExtendedWhenNeeded(shiftLeft64bit.output)
    case 2 => slt.asUInt
    case 3 => sltu.asUInt
    case 4 => xor64bit.output
    case 5 => Mux(servicingRequest.bits.request.instruction(30), shiftRightArithmetic64bit.output, take32signExtendedWhenNeeded(shiftRightLogic64bit.output))
    case 6 => or64bit.output
    case 7 => and64bit.output
  })(funct3Of(servicingRequest.bits.request.instruction))

  val zicsrResult = VecInit.tabulate(4)(_ match {
    case 2 => or64bit.output
    case 3 => and64bit.output
    case _: Int => servicingRequest.bits.request.src1
  })(funct3Of(servicingRequest.bits.request.instruction)(1,0))

  // driving the toMemory interface
  toMemory.ready := servicedRequest.valid
  toMemory.bits := servicedRequest.bits

  // updating servicedRequest register
  when (!toMemoryInterfaceStalled) {
    when (servicingRequestReadyForNextStage) {
      servicedRequest.valid := true.B
      servicedRequest.bits.dataOnly := false.B
      servicedRequest.bits.fwdAddr := servicingRequest.bits.request.fwdAddr
      servicedRequest.bits.instruction := servicingRequest.bits.request.instruction
      when (servicingRequest.bits.request.meta.exception) {
        servicedRequest.bits.result := servicingRequest.bits.request.src1
      }.otherwise {
        servicedRequest.bits.result := MuxCase(sameCycleArithmeticResult, Seq(
          RV64Minstruction(servicingRequest.bits.request.instruction) -> Mux(isIntegerMultiply(servicingRequest.bits.request.instruction), multiply.output.bits.data, divide.output.bits.data),
          isUnconditionalJump(servicingRequest.bits.request.instruction) -> (servicingRequest.bits.request.pc+4.U(XLEN.W)),
          (isMemoryOperation(servicingRequest.bits.request.instruction) || isTypeU(servicingRequest.bits.request.instruction)) -> addition64bit.output,
          isSystem(servicingRequest.bits.request.instruction) -> zicsrResult
        ))
      }
      servicedRequest.bits.toFwd := Mux(RV64Minstruction(servicingRequest.bits.request.instruction),
        (multiply.output.ready || divide.output.ready), /* Appropriate interface will be ready */
        rdFieldPresent(servicingRequest.bits.request.instruction) &&
        !isMemoryOperation(servicingRequest.bits.request.instruction) &&
        !isSystem(servicingRequest.bits.request.instruction))
      servicedRequest.bits.writeData := servicingRequest.bits.request.writeData
      servicedRequest.bits.meta := servicingRequest.bits.request.meta
    }.elsewhen(multiply.output.valid) {
      servicedRequest.valid := true.B
      servicedRequest.bits.dataOnly := true.B
      servicedRequest.bits.fwdAddr := multiply.output.bits.fwdAddr
      servicedRequest.bits.result := multiply.output.bits.data
      servicedRequest.bits.toFwd := true.B
    }.elsewhen(divide.output.valid) {
      servicedRequest.valid := false.B
      servicedRequest.bits.dataOnly := true.B
      servicedRequest.bits.fwdAddr := divide.output.bits.fwdAddr
      servicedRequest.bits.result := divide.output.bits.data
      servicedRequest.bits.toFwd := true.B
    }.otherwise {
      servicedRequest.valid := false.B
    }
  }

  // setting the inputs multiply and divide units
  multiply.inputs.bits.src1 := servicingRequest.bits.request.src1
  multiply.inputs.bits.src2 := servicingRequest.bits.request.src2
  multiply.inputs.bits.mOp := multiply.getmOp(servicingRequest.bits.request.instruction)
  multiply.inputs.valid := isIntegerMultiply(servicingRequest.bits.request.instruction) && servicingRequest.valid && !servicingRequest.bits.executed
  divide.inputs.bits.src1 := servicingRequest.bits.request.src1
  divide.inputs.bits.src2 := servicingRequest.bits.request.src2
  divide.inputs.bits.mOp := divide.getmOp(servicingRequest.bits.request.instruction)
  divide.inputs.valid := isIntegerDivide(servicingRequest.bits.request.instruction) && servicingRequest.valid && !servicingRequest.bits.executed

  // updating the servicingRequest register
  val updateServicingRequest = !servicingRequest.valid || (
    servicingRequestReadyForNextStage && !toMemoryInterfaceStalled
  )
  when (updateServicingRequest) {
    when (stalledRequest.valid) {
      // recovering from a stall, hence when this happens
      // usually the servicingRequest was being occupied by a 
      // different instruction in this cycle
      servicingRequest.valid := true.B
      servicingRequest.bits.request := stalledRequest.bits
      servicingRequest.bits.executed := false.B
    }.elsewhen(fromIssue.fired) {
      servicingRequest.valid := true.B
      servicingRequest.bits.request := fromIssue.bits
      servicingRequest.bits.executed := false.B
    }.elsewhen(servicingRequest.valid) {
      // no instruction availble to service next
      servicingRequest.valid := false.B
    }
  }.elsewhen(servicingRequest.valid) {
    when (RV64Minstruction(servicingRequest.bits.request.instruction)) {
      // waiting until operands are passed to relevant execution unit
      when (multiply.inputs.fire || divide.inputs.fire) {
        // fire will assert by relevant execution unit
        servicingRequest.bits.executed := true.B
      }
    }.otherwise {
      // after one cycle all other instructions will be executed
      servicingRequest.bits.executed := true.B
    }
  }

  val quickForwardable = (servicingRequest.bits.request.instruction(6,4) === BitPat("b0?1")) && !RV64Minstruction(servicingRequest.bits.request.instruction)
  quickForward.valid := servicingRequest.valid && !servicingRequest.bits.request.meta.exception && quickForwardable // OP-IMM, OP, AUIPC, LUI, OP-IMM-32, OP-32
  quickForward.bits.data := Mux(isTypeU(servicingRequest.bits.request.instruction), addition64bit.output, sameCycleArithmeticResult)

  // updaing stalledRequest register
  when (stalledRequest.valid) {
    when (updateServicingRequest) { stalledRequest.valid := false.B }
  }.otherwise {
    when (fromIssue.fired && !updateServicingRequest) {
      stalledRequest.valid := true.B
      stalledRequest.bits := fromIssue.bits
    }
  }

  // driving fromIssue interface
  fromIssue.ready := !stalledRequest.valid

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
  // toFwd.ready := executedRequest.valid && executedRequest.bits.pushToFwd

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

  val executedInstructionStalled = false.B
    // Seq(toFwd, toMemory).map(i => i.fired || !i.ready).reduce(_ && _)

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