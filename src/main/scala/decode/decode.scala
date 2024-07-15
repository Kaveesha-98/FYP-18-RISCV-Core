package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._
import pipeline.decode.utils._

/** definition of all ports can be found here */
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

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
  def rdValid(instruction: UInt) = 
    (instruction(5, 2) === "b1000".U(4.W)) || (instruction(6, 2) === "b01001".U(5.W))
  def rs1FieldPresent(instruction: UInt) = 
    !(
      Cat(instruction(14), instruction(6, 2)) === "b111100".U(6.W) // || // system instructions without rs1

    )
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

  // architectural registers
  val registers = Module(new registerfile)

  // Requesting the data of operand from register file
  registers.rs1 := rs1Of(fromFetch.instruction)
  registers.rs2 := rs2Of(fromFetch.instruction)

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
    val fwdAddr = toExec.robAddr.cloneType
  } Lit(_.valid -> false.B))

  toExec.ready := toExecDriver.valid
  toExec.src1 := toExecDriver.src1
  toExec.src2 := toExecDriver.src2
  toExec.writeData := toExecDriver.writedata
  toExec.instruction := toExecDriver.instruction
  toExec.pc := toExecDriver.pc
  toExec.robAddr := toExecDriver.fwdAddr

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

  // The writeback data of the retired might be beed to
  // to be forwarded to decoding instructions
  val fwdwbDataToDecode = 
    rdValid(writeBackResult.opcode) && (fwdRegMap(writeBackResult.rdAddr).addr === writeBackResult.robAddr) && fwdRegMap(writeBackResult.rdAddr).valid  

  val toExecDriverNext = Wire(toExecDriver.cloneType)
  toExecDriverNext := toExecWaitingBuffer
  when(!toExecWaitingBuffer.valid) {
    toExecDriverNext.src1.data := registers.rs1
    toExecDriverNext.src1.fromRob := fwdRegMap(rs1Of(waitingForRead.instruction)).valid
  }

  val toExecStalled = toExec.ready && !toExec.fired
  when(toExecStalled) {
    // Forwarding data from instruction retire interface, these
    // data will not be available to forwarded after current cycle
    when(writeBackResult.fired && rdValid(writeBackResult.opcode)) {
      Seq(toExecDriver.src1, toExecDriver.src2, toExecDriver.writedata)
      .foreach( src => {
        when(src.fromRob && (src.robAddr === writeBackResult.robAddr)) {
          src.fromRob := false.B
          src.data := writeBackResult.writeBackData
        }
      })
    }
  }

  // Structures from old decode mentioned until core.scala and system.scala can be changed
  val registerFile = Mem(regCount, UInt(dataWidth.W))
  val mstatus = Mem(1, UInt(dataWidth.W))
  val mtvec = Mem(1, UInt(dataWidth.W))
  val csrWriteOut = IO(Output(UInt(64.W)))
}

object DecodeUnit extends App{
  emitVerilog(new decode())
}