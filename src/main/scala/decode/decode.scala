package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._
import pipeline.decode.utils._

/** definition of all ports can be found here */
import pipeline.configuration.coreConfiguration._
import pipeline.ports._
import pipeline.configuration.TypeI
import pipeline.configuration.TypeR
import pipeline.configuration.TypeU

class registerfile extends Module {
  val readPortsInRegFile = 2
  val regFileAddrSize = 5
  // I/O
  val rs1, rs2= IO(Input(UInt(regFileAddrSize.W)))
  val rs1ReadData, rs2ReadData = IO(Output(UInt(XLEN.W)))
  val writeback = IO(Input(new Bundle {
    val valid = Bool()
    val rd = UInt(regFileAddrSize.W)
    val data = UInt(XLEN.W)
  }))

  // Since BRAM has only 1 read ports and only 1 write port
  // To have two read ports, we will instantiate two BRAMs
  // that has identical data across them

  // toConsider: Since the registers do not have a reset 
  // condition, noise may cause to read two values for same
  // architectural register
  // Hence, do we need spend a few cycles after reset to
  // reset all registers in BRAM to common value?
  // Normally software does this part. Maybe move this to Boot ROM

  val regFiles = Seq.fill(readPortsInRegFile)(SyncReadMem(1 << regFileAddrSize, UInt(XLEN.W)))

  (Seq(rs1, rs2) zip regFiles).zip(Seq(rs1ReadData, rs2ReadData))
  .foreach{ case((rs, regfile), readPort) => {
    val doForward = RegNext((writeback.rd === rs) && writeback.valid, false.B)
    // reading
    readPort := Mux(doForward, RegNext(writeback.data), regfile.read(rs))
    // writing
    when(writeback.valid) { regfile.write(writeback.rd, writeback.data) }
  }}
}

/**
  * Functionality - Must communicate the pc of the first instruction to execute
  * through fromFetch port.
  * 
  * All instructions received from fromFetch must be decoded and
  * issued to execution pipeline through toExec.
  * 
  * We accept the predicted path presented by the fetch unit,
  * but only issue through toExec the correct flow path
  * (i.e. we decode the predicted instructions)
  * 
  * Logic outside of the decode will only accept the correct 
  * execution path.
  * i.e. toExec.fire will not trigger if the presented instruction
  * to the toExec does not belong to the correct execution path.
  * 
  * When an incorrect execution path is detected through port
  * 'IncorrectFlow', decode can request the correct instruction
  * from fetch through fromFetch.expected.
  * 
  * If the next instruction after a branch is not available to
  * be presented, then that branch will be marked as mispredicted
  * regardless of the actual prediction.
  * 
  * When fromFetch.expected.valid is high, fromFetch.fire is
  * guranteed to trigger when fetch unit is presenting the correct
  * instruction.
  * 
  * To accept the predicted path from the fetch unit, fromFetch.expected.valid
  * must be driven low.
  * 
  * When an instruction is being issued to the execution pipeline
  * through toExec, a fwdAddr will be assigned to the destination
  * register. To avoid RAW data dependencies, an instruction that
  * is dependent on a transient instruction, then it the result 
  * can be forwarded from forwardUnit when being issued to execution
  * pipeline using the fwdAddr assigned to the source register.
  * 
  * Once an system instruction enters through fromFetch, fromFetch.ready
  * is driven low to stop accepting more instructions from fetch
  * unit. The instruction is then issued to the execution pipeline.
  * Once the instruction is fired from writeBackResult, we execute
  * the instruction.
  * 
  * For Zicsr instructions writeBackData will data read from 
  * registerfile. The single writeport of the registerfile will
  * be used to write data in CSR to register file
  * 
  * Plan to implement illegal instruction and misalign exceptions
  * 
  * Details about the IO can be found on common/ports.scala
  *
  */
class decode extends Module {
  val regFileAddrSize = 5
  def rs1Of(instruction: UInt) = instruction(19, 15)
  def rs2Of(instruction: UInt) = instruction(24, 20)
  def rdOf(instruction: UInt) = instruction(11, 7)
  // rd note valid for stores and conditional branches
  def rdFieldPresent(instruction: UInt) = 
    (instruction(5, 2) === "b1000".U(4.W)) || (instruction(6, 2) === "b01001".U(5.W))
  def rs1FieldPresent(instruction: UInt) = 
    !(
      (Cat(instruction(14), instruction(6, 2)) === "b111100".U(6.W)) || // system instructions without rs1
      (Cat(instruction(6), instruction(4, 2)) === "b0101".U(4.W)) || // LUI and AUIPC
      (instruction(6, 2) === "b11011".U(5.W)) // JAL
      // ecall and ebreak has rs1 field 00000, hence no need to check
    )
  def rs2FieldPresent(instruction: UInt) = 
    ((instruction(6, 5) === "b01".U(2.W)) && (instruction(4, 2) === "b101".U(3.W)) || (instruction(6, 2) === "b11000".U))
  def writeToMemory(instruction: UInt) = instruction(6, 4) === "b010".U(3.W)
  def containUpperImmediate(instruction: UInt) = instruction(4, 2) === "b101".U(3.W)
  def isBranch(instruction: UInt) = instruction(6, 4) === "b110".U(3.W)
  def isSystem(instruction: UInt) = instruction(6, 2) === "b11100".U(5.W)
   /**
    * Inputs and Outputs of the module
    */
  val fromFetch       = IO(new recivInstrFrmFetch)        /** receives instructions from fetch and communicates the pc of the expected instruction */
  val branchRes       = IO(new branchResFrmDecode)        /** sends results of branched to fetch unit */
  val toExec          = IO(new pushInsToPipeline)         /** sends the decoded instruction to the next stage of the pipeline */
  val writeBackResult = IO(new pullCommitFrmRob)          /** receives results to write into the register file */
  val robEmpty = IO(Input(Bool()))
  // val csrWriteOut = IO(Output(csrWriteImmediate.cloneType))
  val decodePC = IO(Output(UInt(64.W)))
  val decodeIns = IO(Output(UInt(32.W)))
  val allowInterrupt = IO(Output(Bool()))
  val branchResolution = IO(Input(new Bundle {
    val valid = Bool()
    val failed = Bool()
    val nextCorrectPC = UInt(XLEN.W)
  }))

  // architectural registers
  val registers = Module(new registerfile)

  // registerfile reads takes one cycle.
  /**
    * Once the rs* fields are given to read from registerfile,
    * it is buffered in waitingForRead for one cycle. After one
    * cycle it is sent to toExecDriver to be presented to toExec
    */
  val waitingForRead = RegInit(new Bundle {
    val valid = Bool()
    val instruction = fromFetch.instruction.cloneType
    val pc = fromFetch.pc.cloneType
  } Lit(_.valid -> false.B)) 

  // These drivers will directly drive toExec port
  val toExecDriver = RegInit(new Bundle {
    val valid = Bool()
    val src1 = toExec.src1.cloneType
    val src2 = toExec.src1.cloneType
    val writedata = toExec.writeData.cloneType
    val instruction = toExec.instruction.cloneType
    val pc = toExec.pc.cloneType
  } Lit(_.valid -> false.B))

  toExec.ready := toExecDriver.valid
  toExec.src1 := toExecDriver.src1
  toExec.src2 := toExecDriver.src2
  toExec.writeData := toExecDriver.writedata
  toExec.instruction := toExecDriver.instruction
  toExec.pc := toExecDriver.pc

  // toExec may not fire, eventhough toExec.ready is high
  /**
    * toExec.fire may not trigger, eventhough toExec.ready is
    * high. This may happen due to pipeline stalls. When this
    * happens we cannot overwrite the existing instruction in
    * toExecDriver. We buffer this instruction in toExecWaitingBuffer
    */
  val toExecWaitingBuffer = RegInit(toExecDriver.cloneType Lit(_.valid -> false.B))

  val fwdRegMap = RegInit(VecInit(Seq.fill(1 << regFileAddrSize)((new Bundle{
    val valid   = Bool()
		val addr  = UInt(robAddrWidth.W)
	}).Lit(
		_.valid -> false.B
	))))
  // update fwdRegMap when an instruction is fired to execution pipeline
  // entry to update will be received through toExec.robAddr
  when(toExec.fired && rdOf(toExecDriver.instruction).orR) {
    fwdRegMap(rdOf(toExecDriver.instruction)).valid := true.B
    fwdRegMap(rdOf(toExecDriver.instruction)).addr := toExec.robAddr
  }
  when(writeBackResult.fired && (fwdRegMap(writeBackResult.rdAddr).addr === writeBackResult.robAddr)) {
    // This data is now valid in registerfile
    fwdRegMap(writeBackResult.rdAddr).valid := false.B
  }

  val toExecDriverNext = Wire(toExecDriver.cloneType)
  toExecDriverNext := toExecWaitingBuffer
  when(!toExecWaitingBuffer.valid) {
    // Processing entry in waitingForRead buffer
    // rs1
    toExecDriverNext.src1.data := Mux(rs1Of(waitingForRead.instruction).orR, registers.rs1, 0.U(XLEN.W))
    toExecDriverNext.src1.fromRob := rs1Of(waitingForRead.instruction).orR && fwdRegMap(rs1Of(waitingForRead.instruction)).valid
    toExecDriverNext.src1.robAddr := fwdRegMap(rs1Of(waitingForRead.instruction)).addr
    when(!rs1FieldPresent(waitingForRead.instruction)) {
      toExecDriverNext.src1.fromRob := false.B
      // we ignore the rs1 field of JAL after issue
      toExecDriverNext.src1.data := Cat(0.U((XLEN-uimmSize).W), rs1Of(waitingForRead.instruction)) // SYSTEM with uimm field
      when(!waitingForRead.instruction(6).asBool) { // AUIPC - PC and LUI - 0
        toExecDriverNext.src1.data := Mux(waitingForRead.instruction(5).asBool, 0.U(64.W), waitingForRead.pc)
      }
    }

    // rs2
    toExecDriverNext.src2.data := Mux(rs2Of(waitingForRead.instruction).orR, registers.rs2, 0.U(XLEN.W))
    toExecDriverNext.src2.fromRob := rs2Of(waitingForRead.instruction).orR && fwdRegMap(rs2Of(waitingForRead.instruction)).valid
    toExecDriverNext.src2.robAddr := fwdRegMap(rs2Of(waitingForRead.instruction)).addr
    when(writeToMemory(waitingForRead.instruction) || !rs2FieldPresent(waitingForRead.instruction)) {
      toExecDriverNext.src2.fromRob := false.B
      toExecDriverNext.src2.data := getImmediate(waitingForRead.instruction, TypeI())
      when(containUpperImmediate(waitingForRead.instruction)) { 
        toExecDriverNext.src2.data := getImmediate(waitingForRead.instruction, TypeU()) 
      }
    }

    // write data
    toExecDriverNext.writedata.data := Mux(rs2Of(waitingForRead.instruction).orR, registers.rs2, 0.U(XLEN.W))
    toExecDriverNext.writedata.fromRob := rs2Of(waitingForRead.instruction).orR && fwdRegMap(rs2Of(waitingForRead.instruction)).valid
    toExecDriverNext.writedata.robAddr := fwdRegMap(rs2Of(waitingForRead.instruction)).addr
    when(!writeToMemory(waitingForRead.instruction)) {
      toExecDriverNext.writedata.fromRob := false.B
    }

    toExecDriverNext.instruction := waitingForRead.instruction
    toExecDriverNext.pc := waitingForRead.pc
  }

  val toExecStalled = toExec.ready && !toExec.fired
  when(toExecStalled) {
    // Forwarding data from instruction retire interface, these
    // data will not be available to forwarded after current cycle
    // in forwardUnit
    when(writeBackResult.fired && rdFieldPresent(writeBackResult.opcode) && writeBackResult.rdAddr.orR) {
      Seq(toExecDriver.src1, toExecDriver.src2, toExecDriver.writedata)
      .foreach( src => {
        when(src.fromRob && (src.robAddr === writeBackResult.robAddr)) {
          src.fromRob := false.B
          src.data := writeBackResult.writeBackData
        }
      })
    }
  }.otherwise {
    toExecDriver := toExecDriverNext
    when(writeBackResult.fired && rdFieldPresent(writeBackResult.opcode) && writeBackResult.rdAddr.orR) {
      Seq(toExecDriver.src1, toExecDriver.src2, toExecDriver.writedata)
      .zip(Seq(toExecDriverNext.src1, toExecDriverNext.src2, toExecDriverNext.writedata))
      .foreach{ case(driver, next) => {
        when(next.fromRob && (next.robAddr === writeBackResult.robAddr)) {
          driver.fromRob := false.B
          driver.data := writeBackResult.writeBackData
        }
      }}
    }
    // Accounting for RAW violations that may occur due to the current
    // instruction being fired to execution pipeline
    when(toExecDriver.valid && rdFieldPresent(toExecDriver.instruction) && rdOf(toExecDriver.instruction).orR) {
      when(rs1FieldPresent(toExecDriverNext.instruction) && (rs1Of(toExecDriverNext.instruction) === rdOf(toExecDriver.instruction))) {
        toExecDriver.src1.fromRob := true.B
        toExecDriver.src1.robAddr := toExec.robAddr
      }
      when(rs2FieldPresent(toExecDriverNext.instruction) && !writeToMemory(toExecDriver.instruction) 
      && (rs2Of(toExecDriverNext.instruction) === rdOf(toExecDriver.instruction))) {
        toExecDriver.src2.fromRob := true.B
        toExecDriver.src2.robAddr := toExec.robAddr
      }
      when(writeToMemory(toExecDriverNext.instruction) && (rs2Of(toExecDriverNext.instruction) === rdOf(toExecDriver.instruction))) {
        toExecDriver.writedata.fromRob := true.B
        toExecDriver.writedata.robAddr := toExec.robAddr
      }
    }
  }

  when(toExecWaitingBuffer.valid) {
    when(toExecStalled) {
      // updating the buffered instruction w.r.t. instructions
      // being retired from writebackResult
      when(writeBackResult.fired && rdFieldPresent(writeBackResult.opcode) && writeBackResult.rdAddr.orR) {
        Seq(toExecWaitingBuffer.src1, toExecWaitingBuffer.src2, toExecWaitingBuffer.writedata)
        .foreach( src => {
          when(src.fromRob && (src.robAddr === writeBackResult.robAddr)) {
            src.fromRob := false.B
            src.data := writeBackResult.writeBackData
          }
        })
      }
    }.otherwise {
      // This buffered entry will be moved to toExecDriver
      toExecWaitingBuffer.valid := false.B
    }
  }.otherwise {
    toExecWaitingBuffer := toExecDriverNext
    // Only stored here when toExec is stalled
    toExecWaitingBuffer.valid := toExecDriverNext.valid && toExecStalled
    when(writeBackResult.fired && rdFieldPresent(writeBackResult.opcode) && writeBackResult.rdAddr.orR) {
      Seq(toExecWaitingBuffer.src1, toExecWaitingBuffer.src2, toExecWaitingBuffer.writedata)
      .zip(Seq(toExecDriverNext.src1, toExecDriverNext.src2, toExecDriverNext.writedata))
      .foreach{ case(driver, next) => {
        when(next.fromRob && (next.robAddr === writeBackResult.robAddr)) {
          driver.fromRob := false.B
          driver.data := writeBackResult.writeBackData
        }
      }}
    }
  }

  val readingFrmRegisters = Wire(waitingForRead.cloneType)
  val bufferedFrmFetch = RegInit(waitingForRead.cloneType Lit(_.valid -> false.B))
  readingFrmRegisters := bufferedFrmFetch
  when(fromFetch.fired) {
    readingFrmRegisters.valid := true.B
    readingFrmRegisters.instruction := fromFetch.instruction
    readingFrmRegisters.pc := fromFetch.pc
  }
  registers.rs1 := rs1Of(readingFrmRegisters.instruction)
  registers.rs2 := rs2Of(readingFrmRegisters.instruction)

  waitingForRead := readingFrmRegisters
  // Register files are not read when there is an execution pipeline stall
  // unless there are no entries in waitingForRead and toExecWaitingBuffer.
  // In which case we do one additional read.
  when(toExecStalled && (waitingForRead.valid || toExecWaitingBuffer.valid)) { 
    waitingForRead.valid := false.B 
  }

  when(bufferedFrmFetch.valid) {
    when(!toExecStalled) { bufferedFrmFetch.valid := false.B }
  }.otherwise {
    bufferedFrmFetch.valid := fromFetch.fired && toExecStalled && (waitingForRead.valid || toExecWaitingBuffer.valid)
  }
  fromFetch.ready := !bufferedFrmFetch.valid

  val expectingFrmFetch = RegInit(fromFetch.expected.cloneType Lit(_.valid -> true.B, _.pc -> instructionBase.U))
  // Unless the accepted instruction from fetch is a branch,
  // we increment expectingFrmFetch by 4 for accepted instruction
  when(fromFetch.fired) {
    expectingFrmFetch.pc := expectingFrmFetch.pc + 4.U
    // after a branch has been accepted, we will then accept the path determined
    // by fetch unit, until a predicted branch is resolved to be mispredicted
    when(isBranch(fromFetch.instruction)) { expectingFrmFetch.valid := false.B }
  }
  // Misprediction reported from execution pipeline
  when(branchResolution.valid && branchResolution.failed) {
    // Flushing the decode of fetched instructions
    /**
      * Although rare, there can be an instance where the fetch
      * correctly detected the direction, but it was resolved failed
      * because the predicted next instruction could not be fetched
      * in time to properly evaluate the prediction by the execution
      * pipeline.
      * There can be a performance hit here.
      */
    Seq(bufferedFrmFetch.valid, waitingForRead.valid, toExecWaitingBuffer.valid, toExecDriver.valid)
    .foreach(_ := false.B)
    expectingFrmFetch.valid := true.B
    expectingFrmFetch.pc := branchResolution.nextCorrectPC
  }

  // Stages of handling an instruction with SYSTEM opcode
  val noSysIns :: sysInDecode :: sysInExec :: Nil = Enum(3)
  val sysInsStatus = RegInit(noSysIns)
  switch(sysInsStatus) {
    is(noSysIns) { when(fromFetch.fired && isSystem(fromFetch.instruction)) { sysInsStatus := sysInDecode }}
    is(sysInDecode) {
      // system instruction flushed from decode
      when(branchResolution.valid && branchResolution.failed) { sysInsStatus := noSysIns }
      // system instruction pushed to pipeline
      when(toExec.fired && isSystem(toExec.instruction)) { sysInsStatus := sysInExec }
    }
    is(sysInExec) { when(writeBackResult.fired && isSystem(writeBackResult.opcode)) { sysInsStatus := noSysIns }}
  }

  /**
    * TODO
    * 1. Flushing decode unit after misprediction
    * 2. Updating registerfile instruction retires
    * 3. Retiring system instructions
    * 4. Implemention Zicsr
    * 5. Implementation ecall, mret, ebreak 
    * 6. Way to ecall illegal instructions
    */

  // Structures from old decode mentioned until core.scala and system.scala can be changed
  val registerFile = Mem(regCount, UInt(dataWidth.W))
  val mstatus = Mem(1, UInt(dataWidth.W))
  val mtvec = Mem(1, UInt(dataWidth.W))
  val csrWriteOut = IO(Output(UInt(64.W)))
}

object DecodeUnit extends App{
  emitVerilog(new decode())
}