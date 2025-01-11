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
import pipeline.configuration.mcauseEncodings
import pipeline.configuration.priviledgeEncodings
import pipeline.configuration.CSRAddresses
import pipeline.decode.constants.opcode5MSBs.jal

abstract class CSRRegister {
  val address: Int
  def read(): UInt
  def write(wbData: UInt): Unit
}

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
class decode(val hartid:Int = 0) extends Module {
  val regFileAddrSize = 5
  def rs1Of(instruction: UInt) = instruction(19, 15)
  def rs2Of(instruction: UInt) = instruction(24, 20)
  def rdOf(instruction: UInt) = instruction(11, 7)
  def rs1FieldPresent(instruction: UInt) = 
    !(
      ((instruction(6, 2) === "b11100".U(6.W)) && (funct3Of(instruction).asSInt <= 0.S)) || // system instructions without rs1
      (Cat(instruction(6), instruction(4, 2)) === "b0101".U(4.W)) || // LUI and AUIPC
      (instruction(6, 2) === "b11011".U(5.W)) // JAL
      // ecall and ebreak has rs1 field 00000, hence no need to check
    )
  def rs2FieldPresent(instruction: UInt) = 
    ((instruction(6, 5) === "b01".U(2.W)) && (instruction(4, 2) === "b101".U(3.W)) || (instruction(6, 2) === "b11000".U))
  def writeToMemory(instruction: UInt) = instruction(6, 4) === "b010".U(3.W)
  def containUpperImmediate(instruction: UInt) = instruction(4, 2) === "b101".U(3.W)
  def isSystem(instruction: UInt) = instruction(6, 2) === "b11100".U(5.W)
  def isIllegal(instruction: UInt) = false.B
  def isSystemCall(instruction: UInt) = Cat(rs2Of(instruction), funct3Of(instruction), opcode5BitsOf(instruction)) === "b0001000011100".U(13.W)
  def isJAL(instruction: UInt) = instruction(6,2) === "b11011".U(5.W)
  /* interrupts are enterred as a custom instruction will lower 30 bits equal to ecall */
  def isECALLorInterrupt(instruction: UInt) = instruction(30, 0) === "h00000073".U(30.W)
  def isMRET(instruction: UInt) = instruction === "h3020073".U(32.W)
  def zicsrMatch(instruction: UInt, address: Int) = getImmediateTypeI(instruction) === address.U(12.W)
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

  // Once a register read is requested, all instructions are moved
  // here. If registerfile.readPort is blocked (waitOntoExecBuffer.valid in next cycle),
  // then new instruction is sent to stalledInstructionfromFetch. 
  // If we have a branch misprediction, then we flush the new instruction
  // fired from fromFetch instead.
  // All instructions can only occupy this buffer only for one cycle
  // There are 3 destinations (mutually exclusive) for an instruction occupying this buffer
  //  1. toExecBuffer: when(!toExecBuffer.valid || toExec.fired)
  //  2. waitOntoExecBuffer
  //  3. flushed due to branch mis-prediction
  // There are 2 sources for this 
  //  1. fromFetch: registerfile.readPort is not blocked
  //  2. stalledInstructionfromFetch: (registerfile.readPort is not blocked) and stalledInstructionfromFetch.valid
  val waitOnReadBuffer = RegInit(new Bundle {
    val valid = Bool()
    val pc = UInt(XLEN.W)
    val instruction = UInt(ILEN.W)
    val meta = new pipeline.ports.meta
  } Lit(_.valid -> false.B))

  // This is to handle stalls when a toExec stalls and results in no space for 
  // register file reads for the instruction currently fired instruction. This
  // instruction will occupy this buffer until there is space for registerfile
  // reads.
  // When this is occupyied no new instructions are accepted fromFetch.
  // waitOnReadBuffer is the next destination for any instruction occupying this
  // buffer.
  // fromFetch is the only source for this buffer. When waitOntoExecBuffer.valid
  // is true in next cycle.
  val stalledInstructionfromFetch = RegInit(waitOnReadBuffer.cloneType.Lit(_.valid -> false.B))

  // stall fromFetch when stalledInstructionfromFetch is occupied
  // fromFetch.ready := !stalledInstructionfromFetch.valid

  // requesting architectural register read for each new instruction.
  // This requirces one cycle. The instruction is sent to waitOnReadBuffer
  // so that we can pipeline the read.
  // When there is a stalled instruction that didn't finish its read, it gets
  // priority, however fromFetch.fired show assert at this moment
  registers.rs1(rs1Of(Mux(stalledInstructionfromFetch.valid, stalledInstructionfromFetch.instruction, fromFetch.instruction)))
  registers.rs2(rs2Of(Mux(stalledInstructionfromFetch.valid, stalledInstructionfromFetch.instruction, fromFetch.instruction)))

  // This is to handle the case where when toExec is stalled (i.e. toExec.ready && !toExec.fired)
  // and the results from register read of instruction in waitOnReadBuffer requires to be stored
  // somewhere. The results will be stored here.
  // Only one destination for instructions stored here, which is, toExecBuffer, when (toExec.fired)
  // IMPORTANT waitOntoExecBuffer.valid => toExecBuffer.valid
  // Only one source, which is, waitOnReadBuffer, when toExec is stalled (ie toExec.ready && !toExec.fired)
  val waitOntoExecBuffer = RegInit(new Bundle {
    val valid = Bool()
    // IMPORTANT - value of x0 should never be issued with *.fromRob asserted
    val src1        = (new Bundle {
      val fromFwd = Bool()
      val data = UInt(XLEN.W)
      val fwdAddr = UInt(fwdAddrWidth.W)  
    })               // {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1}
    val src2        = (src1.cloneType)        // {jalr, jal - 4.U}, {loads, stores, iops*, auipc - immediate}, {rops* - rs2}
    val writeData   = (src1.cloneType)
    val instruction = (UInt(ILEN.W))
    val pc          = (UInt(XLEN.W))
    val meta = (new pipeline.ports.meta)
  } Lit(_.valid -> false.B))

  val registerReadsBlockedFromNextCycle = toExec.ready && !toExec.fired && waitOnReadBuffer.valid // => waitOntoExecBuffer.valid on next cycle
  val registerReadsNowBlocked = waitOntoExecBuffer.valid
  // CAUTION: We assume branch resolutions almost always happens with the next predicted instruction
  // If we are wrong, then we have a performance loss
  val flushingInstructions = branchResolution.valid && branchResolution.failed
  
  // Writing to stalledInstructionfromFetch, all conditions should be mutually exclusive
  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    // When we see an exception all instructions also have to be flushed
    stalledInstructionfromFetch.valid := false.B
  }.elsewhen (!stalledInstructionfromFetch.valid && !flushingInstructions) {
    // Writes only happen when this buffer is empty
    when (registerReadsBlockedFromNextCycle || registerReadsNowBlocked) {
      // Only from fromFetch
      stalledInstructionfromFetch.instruction := fromFetch.instruction
      stalledInstructionfromFetch.meta := fromFetch.meta
      stalledInstructionfromFetch.pc := fromFetch.pc
      stalledInstructionfromFetch.valid := fromFetch.fired
    }
  }.otherwise {
    // freeing the buffer
    when (!registerReadsNowBlocked || flushingInstructions) {
      stalledInstructionfromFetch.valid := false.B
    }
  }

  // Writing to waitOnReadBuffer
  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    waitOnReadBuffer.valid := false.B
  }.elsewhen ((!(registerReadsBlockedFromNextCycle || registerReadsNowBlocked)) && !flushingInstructions) {
    // all these conditions should be mutually exclusive
    when (fromFetch.fired) {
      waitOnReadBuffer.instruction := fromFetch.instruction
      waitOnReadBuffer.meta := fromFetch.meta
      waitOnReadBuffer.pc := fromFetch.pc
      waitOnReadBuffer.valid := true.B
    }.elsewhen (stalledInstructionfromFetch.valid) {
      waitOnReadBuffer.instruction := stalledInstructionfromFetch.instruction
      waitOnReadBuffer.meta := stalledInstructionfromFetch.meta
      waitOnReadBuffer.pc := stalledInstructionfromFetch.pc
      waitOnReadBuffer.valid := true.B
    }.otherwise {
      waitOnReadBuffer.valid := false.B
    }
  }.otherwise {
    // instruction flushes and stalls
    waitOnReadBuffer.valid := false.B
  }

  val MTIP = Input(Bool())

  val misa = getMISA.U(XLEN.W)
  val mvendorid = vendorid.U(32.W)
  val marchid = archid.U(XLEN.W)
  val mimpid = impid.U(XLEN.W)
  val mhartid = hartid.U(XLEN.W)
  val mstatus = RegInit(mstatusInitial.U(XLEN.W))
  val mtvec = RegInit(0.U(XLEN.W))
  val medeleg = 0.U(XLEN.W)
  val mideleg = 0.U(XLEN.W)
  val mip = Cat(0.U((XLEN-8).W), MTIP.asUInt, 0.U(7.W))
  val mie = RegInit(0.U(XLEN.W))
  val mtinst = 0.U(XLEN.W)
  val mtval2 = 0.U(XLEN.W)
  // TODO: mcounteren can cause illegal instruction when trying to
  // read some other CSR registers
  val mcounteren = RegInit(0.U(32.W))
  val mcountinhibit = RegInit(0.U(32.W))
  val mcycle = RegInit(0.U(XLEN.W))
  val minstret = RegInit(0.U(XLEN.W))
  val mscratch = RegInit(0.U(XLEN.W))
  val mepc = RegInit(0.U(XLEN.W))
  val mcasue = RegInit(0.U(XLEN.W))
  val mtval = RegInit(0.U(XLEN.W))
  val mconfigptr = 0.U(XLEN.W)
  val menvcfg = 0.U(64.W)
  val mseccfg = 0.U(64.W)

  // We have two sources
  //  1. registerfile: default
  //  2. registers.writeback (writeback data for retiring instruction)
  //      This is the most updated data for the corresponding source from the retired instructions.
  //      This data has to be forwarded when needed
  def getRegisterValue(srcAddr: UInt, registerfileOutput: UInt) = 
    Mux(registers.writeback.valid && (registers.writeback.rd === srcAddr), registers.writeback.data, registerfileOutput)

  def readCSR(instruction: UInt) = 
    MuxLookup(instruction, 0.U, Seq(
      CSRAddresses.cycle.U -> mcycle,
      CSRAddresses.instret.U -> minstret,
      CSRAddresses.mvendorid.U -> mvendorid,
      CSRAddresses.marchid.U -> marchid,
      CSRAddresses.mimpid.U -> mimpid,
      CSRAddresses.mhartid.U -> mhartid,
      CSRAddresses.mconfigptr.U -> mconfigptr,
      CSRAddresses.mstatus.U -> mstatus,
      CSRAddresses.misa.U -> misa,
      CSRAddresses.medeleg.U -> medeleg,
      CSRAddresses.mideleg.U -> mideleg,
      CSRAddresses.mie.U -> mie,
      CSRAddresses.mtvec.U -> mtvec,
      CSRAddresses.mcounteren.U -> mcounteren,
      CSRAddresses.mscratch.U -> mscratch,
      CSRAddresses.mepc.U -> mepc,
      CSRAddresses.mcause.U -> mcasue,
      CSRAddresses.mtval.U -> mtval,
      CSRAddresses.mip.U -> mip,
      CSRAddresses.mtinst.U -> mtinst,
      CSRAddresses.mtval2.U -> mtval2,
      CSRAddresses.menvcfg.U -> menvcfg,
      CSRAddresses.mseccfg.U -> mseccfg,
      CSRAddresses.mcycle.U -> mcycle,
      CSRAddresses.minstret.U -> minstret,
      CSRAddresses.mcountinhibit.U -> mcountinhibit
    ))

  val registerHasFwdAddr = RegInit(VecInit.fill(1 << regFileAddrSize)(false.B))
  val fwdAddrOfRegisters = RegInit(VecInit.fill(1 << regFileAddrSize)(0.U(fwdAddrWidth.W)))

  val registerReadResults = Wire(waitOntoExecBuffer.cloneType)
  registerReadResults.valid := waitOnReadBuffer.valid
  registerReadResults.instruction := waitOnReadBuffer.instruction
  registerReadResults.meta := waitOnReadBuffer.meta
  registerReadResults.pc := waitOnReadBuffer.pc
  when (rs1FieldPresent(waitOnReadBuffer.instruction)) {
    registerReadResults.src1.data := getRegisterValue(rs1Of(waitOnReadBuffer.instruction), registers.rs1ReadData)
  }. otherwise {
    registerReadResults.src1.data := 0.U // default
    switch (opcode5BitsOf(waitOnReadBuffer.instruction)) {
      is (opcode5MSBs.auipc.U(5.W)) { registerReadResults.src1.data := waitOnReadBuffer.pc }
      is (opcode5MSBs.system.U(5.W)) { registerReadResults.src1.data := getImmediateUimm(waitOnReadBuffer.instruction) }
    }
  }
  when (rs2FieldPresent(waitOnReadBuffer.instruction) && !writeToMemory(waitOnReadBuffer.instruction)) {
    registerReadResults.src2.data := getRegisterValue(rs2Of(waitOnReadBuffer.instruction), registers.rs2ReadData)
  }.otherwise {
    registerReadResults.src2.data := Seq(
      (isTypeI(waitOnReadBuffer.instruction), getImmediateTypeI(waitOnReadBuffer.instruction)),
      (isTypeS(waitOnReadBuffer.instruction), getImmediateTypeS(waitOnReadBuffer.instruction)),
      // (isTypeB(waitOnReadBuffer.instruction), getImmediateTypeB(waitOnReadBuffer.instruction)), // Type has rs2 field
      (isTypeU(waitOnReadBuffer.instruction), getImmediateTypeU(waitOnReadBuffer.instruction)),
      (isTypeJ(waitOnReadBuffer.instruction), getImmediateTypeJ(waitOnReadBuffer.instruction)),
      (isSystem(waitOnReadBuffer.instruction), readCSR(waitOnReadBuffer.instruction))
    ).foldLeft(0.U){ case (other, (typeMatch, immediate)) => Mux(typeMatch, immediate, other)}
  }
  when (writeToMemory(waitOnReadBuffer.instruction)) {
    registerReadResults.writeData.data := getRegisterValue(rs2Of(waitOnReadBuffer.instruction), registers.rs2ReadData)
  }.otherwise {
    registerReadResults.writeData.data := 0.U
  }
  // These are don't care wires for now
  // We get the most upto date data from the retired instructions. Validity of the data
  // will be checked when this data is moved to toExecBuffer
  registerReadResults.src1.fwdAddr := 0.U
  registerReadResults.src1.fromFwd := false.B
  registerReadResults.src2.fwdAddr := 0.U
  registerReadResults.src2.fromFwd := false.B
  registerReadResults.writeData.fwdAddr := 0.U
  registerReadResults.writeData.fromFwd := false.B

  val toExecInterfaceStalled = toExec.ready && !toExec.fired

  // writing to waitOntoExecBuffer
  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    waitOntoExecBuffer.valid := false.B
  }.elsewhen (waitOntoExecBuffer.valid) {
    when (flushingInstructions || !toExecInterfaceStalled) {
      // instruction either flushed or is moved to toExecBuffer
      waitOntoExecBuffer.valid := false.B
    }.otherwise {
      // updating data fields w.r.t. newly retired instructions
      when(registers.writeback.valid) {
        when (
          rs1FieldPresent(waitOntoExecBuffer.instruction) && 
          (rs1Of(waitOntoExecBuffer.instruction) === registers.writeback.rd)) {

          waitOntoExecBuffer.src1.data := registers.writeback.data
        }
        when (
          rs2FieldPresent(waitOntoExecBuffer.instruction) && 
          (rs2Of(waitOntoExecBuffer.instruction) === registers.writeback.rd) 
        ) {

          when (writeToMemory(waitOntoExecBuffer.instruction)) {
            waitOntoExecBuffer.writeData.data := registers.writeback.data
          }.otherwise {
            waitOntoExecBuffer.src2.data := registers.writeback.data
          }
        }
      }
    }
  }.otherwise {
    when (toExecInterfaceStalled && waitOnReadBuffer.valid) {
      // register reads will be blocked in the next cycle
      // IMPORTANT: At least one of the below buffers should be free

      // Data from the retired instruction in this cycle is forwarded
      // when waitOntoExecBuffer is written
      waitOntoExecBuffer := waitOnReadBuffer
    }
  }

  // Few cases to consider:
  // 1. If the address is dependent on the firing instruction, then we need forwarding 
  // 2. If registerHasFwdAddr(address) is true, then we need forwarding unless the dependency
  //      is because of the currently retiring instruction
  def registerNeedsForwarding(address: UInt) = 
    (rdFieldPresent(toExec.instruction) && (rdOf(toExec.instruction) === address)) || 
    (registerHasFwdAddr(address) && !((fwdAddrOfRegisters(address) === writeBackResult.fwdAddr) && registers.writeback.valid))

  def getFwdAddrOfRegister(address: UInt) = 
    Mux(rdFieldPresent(toExec.instruction) && (rdOf(toExec.instruction) === address), toExec.fwdAddr, fwdAddrOfRegisters(address))

  // This drivers the toExec interface
  // There are 2 sources
  //  1. waitOnReadBuffer: by default
  //  2. waitOntoExecBuffer: When recovering from a stall
  val toExecBuffer = RegInit(waitOntoExecBuffer.cloneType.Lit(_.valid -> false.B))
  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    toExecBuffer.valid := false.B
  }.elsewhen (toExecInterfaceStalled) {
    when (flushingInstructions) {
      toExecBuffer.valid := false.B
    }.elsewhen(writeBackResult.fired) {
      // updating data from writeback interface
      // *fromFwd and *fwdAddr are only valid on toExecBuffer
      when (toExecBuffer.src1.fromFwd && (toExecBuffer.src1.fwdAddr === writeBackResult.fwdAddr)) {
        toExecBuffer.src1.data := writeBackResult.writeBackData
        toExecBuffer.src1.fromFwd := false.B
      }
      when (toExecBuffer.src2.fromFwd && (toExecBuffer.src2.fwdAddr === writeBackResult.fwdAddr)) {
        toExecBuffer.src2.data := writeBackResult.writeBackData
        toExecBuffer.src2.fromFwd := false.B
      }
      when (toExecBuffer.writeData.fromFwd && (toExecBuffer.writeData.fwdAddr === writeBackResult.fwdAddr)) {
        toExecBuffer.writeData.data := writeBackResult.writeBackData
        toExecBuffer.writeData.fromFwd := false.B
      }
    }
  }.elsewhen(flushingInstructions) {
    toExecBuffer.valid := false.B
  }.otherwise {
    when (waitOntoExecBuffer.valid) {
      // recovering from stalls
      toExecBuffer := waitOntoExecBuffer
      // forwarding from writebackresult
      when (registers.writeback.valid) {
        when (rs1FieldPresent(waitOntoExecBuffer.instruction) && (rs1Of(waitOntoExecBuffer.instruction) === registers.writeback.rd)) {
          toExecBuffer.src1.data := registers.writeback.data
        }
        when (rs2FieldPresent(waitOntoExecBuffer.instruction) && (rs2Of(waitOntoExecBuffer.instruction) === registers.writeback.rd)) {
          when (writeToMemory(waitOntoExecBuffer.instruction)) {
            toExecBuffer.writeData.data := registers.writeback.data
          }.otherwise {
            toExecBuffer.src2.data := registers.writeback.data
          }
        }
      }
      // Do we need forwarding for registers from pipeline
      when (rs1FieldPresent(waitOntoExecBuffer.instruction)) {
        toExecBuffer.src1.fromFwd := registerNeedsForwarding(rs1Of(waitOntoExecBuffer.instruction))
        toExecBuffer.src1.fwdAddr := getFwdAddrOfRegister(rs1Of(waitOntoExecBuffer.instruction))
      }.otherwise {
        toExecBuffer.src1.fromFwd := false.B
        toExecBuffer.src1.fwdAddr := false.B
      }
      when (rs2FieldPresent(waitOntoExecBuffer.instruction)) {
        when (writeToMemory(waitOntoExecBuffer.instruction)) {
          toExecBuffer.writeData.fromFwd := registerNeedsForwarding(rs2Of(waitOntoExecBuffer.instruction))
          toExecBuffer.writeData.fwdAddr := getFwdAddrOfRegister(rs2Of(waitOntoExecBuffer.instruction))
        }.otherwise {
          toExecBuffer.src2.fromFwd := registerNeedsForwarding(rs2Of(waitOntoExecBuffer.instruction))
          toExecBuffer.src2.fwdAddr := getFwdAddrOfRegister(rs2Of(waitOntoExecBuffer.instruction))
        }
      }.otherwise {
        toExecBuffer.writeData.fromFwd := false.B
        toExecBuffer.writeData.fwdAddr := false.B
        toExecBuffer.src2.fromFwd := false.B
        toExecBuffer.src2.fwdAddr := false.B
      }
    }.otherwise {
      // normal operation
      toExecBuffer := registerReadResults
      // forwarding data from writeBackResult already done
      // Do we need forwarding for registers from pipeline
      when (rs1FieldPresent(registerReadResults.instruction)) {
        toExecBuffer.src1.fromFwd := registerNeedsForwarding(rs1Of(registerReadResults.instruction))
        toExecBuffer.src1.fwdAddr := getFwdAddrOfRegister(rs1Of(registerReadResults.instruction))
      }.otherwise {
        toExecBuffer.src1.fromFwd := false.B
        toExecBuffer.src1.fwdAddr := false.B
      }
      when (rs2FieldPresent(registerReadResults.instruction)) {
        when (writeToMemory(registerReadResults.instruction)) {
          toExecBuffer.writeData.fromFwd := registerNeedsForwarding(rs2Of(registerReadResults.instruction))
          toExecBuffer.writeData.fwdAddr := getFwdAddrOfRegister(rs2Of(registerReadResults.instruction))
        }.otherwise {
          toExecBuffer.src2.fromFwd := registerNeedsForwarding(rs2Of(registerReadResults.instruction))
          toExecBuffer.src2.fwdAddr := getFwdAddrOfRegister(rs2Of(registerReadResults.instruction))
        }
      }.otherwise {
        toExecBuffer.writeData.fromFwd := false.B
        toExecBuffer.writeData.fwdAddr := false.B
        toExecBuffer.src2.fromFwd := false.B
        toExecBuffer.src2.fwdAddr := false.B
      }
    }
  }
  // Driving toExec
  toExec.ready := toExecBuffer.valid
  toExec.instruction := toExecBuffer.instruction
  toExec.meta := toExecBuffer.meta
  toExec.pc := toExecBuffer.pc
  toExec.src1.data := toExecBuffer.src1.data
  toExec.src1.fromRob := toExecBuffer.src1.fromFwd
  toExec.src1.robAddr := toExecBuffer.src1.fwdAddr
  toExec.src2.data := toExecBuffer.src2.data
  toExec.src2.fromRob := toExecBuffer.src2.fromFwd
  toExec.src2.robAddr := toExecBuffer.src2.fwdAddr
  toExec.writeData.data := toExecBuffer.writeData.data
  toExec.writeData.fromRob := toExecBuffer.writeData.fromFwd
  toExec.writeData.robAddr := toExecBuffer.writeData.fwdAddr

  val expected = RegInit(fromFetch.expected.cloneType.Lit(_.valid -> true.B, _.pc -> instructionStart.U(XLEN.W)))
  // below will only assert for one cycle (and only one should assert at a time)
  val getmtvec /* start interrupt/exception handler */, getxepc/* return from interrupt/exception handler */ = Wire(Bool())
  // TODO: Currently the only supported exception is ECALL, when this changed, this has to be rethinked
  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    expected.pc := mtvec
    expected.valid := true.B
  }.elsewhen (flushingInstructions) {
    expected.pc := branchResolution.nextCorrectPC
    expected.valid := true.B
  }.elsewhen (getmtvec) {
    expected.pc := mtvec
    expected.valid := true.B
  }.elsewhen(getxepc) {
    expected.pc := mepc
    expected.valid := true.B
  }.elsewhen(fromFetch.fired) {
    expected.pc := Mux(isJAL(fromFetch.instruction), fromFetch.pc + getImmediateTypeJ(fromFetch.instruction), fromFetch.pc+4.U)
    expected.valid := !isBranch(fromFetch.instruction) || isJAL(fromFetch.instruction)
  }

  when (writeBackResult.fired && writeBackResult.execptionOccured) {
    registerHasFwdAddr.foreach(_ := false.B)
  }.otherwise {

    when (!flushingInstructions && toExec.fired) {
      // toExec has priority
      registerHasFwdAddr(rdOf(toExec.instruction)) := true.B
      fwdAddrOfRegisters(rdOf(toExec.instruction)) := toExec.fwdAddr
    }
    
    when(
      !(!flushingInstructions && toExec.fired && (rdOf(toExec.instruction) === rdOf(writeBackResult.instruction)) && rdFieldPresent(toExec.instruction)) && 
      (writeBackResult.fired && rdFieldPresent(writeBackResult.instruction) && !isSystem(writeBackResult.instruction))
    ) {
      when (registerHasFwdAddr(rdOf(writeBackResult.instruction)) && (writeBackResult.fwdAddr === fwdAddrOfRegisters(rdOf(writeBackResult.instruction)))) {
        registerHasFwdAddr(rdOf(writeBackResult.instruction)) := false.B // decode now has most upto date data
      }
    }

  }

  // Only exception we consider for now is ecall
  getmtvec := fromFetch.fired && isECALLorInterrupt(fromFetch.instruction)
  getxepc := fromFetch.fired && isMRET(fromFetch.instruction)

  // This will always be ready
  writeBackResult.ready := true.B

  val pcOfNextInstructionToRetire = RegInit(instructionStart.U(XLEN.W))
  when (writeBackResult.fired) {
    when (writeBackResult.execptionOccured) { 
      pcOfNextInstructionToRetire := mtvec
    }.elsewhen(isMRET(writeBackResult.instruction)) {
      pcOfNextInstructionToRetire := mepc
    }.otherwise {
      pcOfNextInstructionToRetire := writeBackResult.nextPC
    }
  }


  val currentPriviledge = RegInit(priviledgeEncodings.machine.U(priviledgeEncodings.bitSize.W))
  when (writeBackResult.fired) {
    when (writeBackResult.execptionOccured) {
      currentPriviledge := priviledgeEncodings.machine.U
    }.elsewhen(isMRET(writeBackResult.instruction)) {
      currentPriviledge := getMPPfromMSTATUS(mstatus)
    }
  }

  // read and store the CSR value read when the retired instruction was decoded
  val storedCSRValue = Reg(UInt(XLEN.W))
  when (waitOnReadBuffer.valid && isSystem(waitOnReadBuffer.instruction)) { storedCSRValue := readCSR(waitOnReadBuffer.instruction) }

  registers.writeback.valid := writeBackResult.fired && rdFieldPresent(writeBackResult.instruction) && !writeBackResult.execptionOccured
  registers.writeback.rd := rdOf(writeBackResult.instruction)
  registers.writeback.data := Mux(isSystem(writeBackResult.instruction), storedCSRValue, writeBackResult.writeBackData)

  // Drive fromFetch
  // We only take in one system inctruction from fromFetch at a time
  object systemProcessingStates {
    val none :: decoding :: executing :: Nil = Enum(3)
  }
  val systemProcessingState = RegInit(systemProcessingStates.none)
  when (systemProcessingState === systemProcessingStates.none) {
    // default state
    fromFetch.ready := !stalledInstructionfromFetch.valid
  }.otherwise {
    // Once get a system inctruction from fromFetch. We stop accepting
    // new instructions until either the accpeted one is flushed or
    // it is retired
    fromFetch.ready := false.B
  }
  fromFetch.expected := expected
  switch (systemProcessingState) {
    is (systemProcessingStates.none) { 
      when (fromFetch.fired && isSystem(fromFetch.instruction) && !flushingInstructions) {
        systemProcessingState := systemProcessingStates.decoding
      }
    }
    is (systemProcessingStates.decoding) {
      // There should be only one system instruction is decode at most
      when (flushingInstructions || (writeBackResult.fired && writeBackResult.execptionOccured)) {
        // when there is instruction flush due to an older instruction, then
        // we revert back to none state
        systemProcessingState := systemProcessingStates.none
      }.elsewhen (toExec.fired && isSystem(toExec.instruction)) {
        systemProcessingState := systemProcessingStates.executing
      }
    }
    is (systemProcessingStates.executing) {
      // There should only be one sytem instruction is the execution pipeline
      // at most
      when (writeBackResult.fired && (isSystem(writeBackResult.instruction) || writeBackResult.execptionOccured)) {
        systemProcessingState := systemProcessingStates.none
      }
    }
  }


  // Updating CSRs
  // minstret
  val minstretInhibited = mcountinhibit(2).asBool
  when (writeBackResult.fired) {
    when (isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction)
    && zicsrMatch(writeBackResult.instruction, CSRAddresses.minstret)) {
      minstret := writeBackResult.writeBackData
    }.elsewhen (!minstretInhibited && !writeBackResult.meta.exception) {
      minstret := minstret + 1.U
    }
  }

  // mcycle
  val mcycleInhibited = mcountinhibit(0).asBool
  when (
    writeBackResult.fired && !writeBackResult.meta.exception && isSystem(writeBackResult.instruction) &&
    rdFieldPresent(writeBackResult.instruction) && zicsrMatch(writeBackResult.instruction, CSRAddresses.mcycle)
  ) {
    mcycle := writeBackResult.writeBackData
  }.elsewhen (!mcycleInhibited) {
    mcycle := mcycle + 1.U
  }

  // mtval
  when (writeBackResult.fired) {
    when (writeBackResult.meta.exception) {
      mcasue := writeBackResult.writeBackData // Have to make sure this happens
    }.elsewhen(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mcause)) {
      mcasue := writeBackResult.writeBackData
    }
  }

  // mcause
  when (writeBackResult.fired) {
    when (writeBackResult.meta.exception) {
      mcasue := writeBackResult.meta.getMCAUSE()
    }.elsewhen(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mcause)) {
      mcasue := writeBackResult.writeBackData
    }
  }

  // mepc
  when (writeBackResult.fired) {
    when (writeBackResult.meta.exception) {
      mepc := pcOfNextInstructionToRetire // Interrupted instruction pc
    }.elsewhen(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mepc)) {
      mepc := writeBackResult.writeBackData
    }
  }

  // mscratch
  // Only software writes to this
  when (writeBackResult.fired && !writeBackResult.meta.exception) {
    when(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mscratch)) {
      mscratch := writeBackResult.writeBackData
    }
  }

  // mcounteren
  // Only software writes to this
  when (writeBackResult.fired && !writeBackResult.meta.exception) {
    when(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mcounteren)) {
      mcounteren := formatBeforeWritingToMCOUNTEREN(writeBackResult.writeBackData, mcounteren)
    }
  }


  // mtvec
  // Only software can change this
  when (writeBackResult.fired && !writeBackResult.meta.exception) {
    when(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mtvec)) {
      mtvec := formatBeforeWritingToMTVEC(writeBackResult.writeBackData, mtvec)
    }
  }

  // mie
  // Only software changes this
  when (writeBackResult.fired && !writeBackResult.meta.exception) {
    when(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mie)) {
      mie := formatBeforeWritingToMIE(writeBackResult.writeBackData, mie)
    }
  }


  // medeleg and mideleg are all RO zero when S not implemented
  // misa is a constant throughout runtime

  // mstatus
  when (writeBackResult.fired) {
    when (writeBackResult.meta.exception) {
      mstatus := setMSTATUStoHandleTrap(mstatus, currentPriviledge)
    }.elsewhen(isMRET(writeBackResult.instruction)) {
      mstatus := setMSTATUSafterTrapReturn(mstatus)
    }.elsewhen(isSystem(writeBackResult.instruction) && rdFieldPresent(writeBackResult.instruction) && 
    zicsrMatch(writeBackResult.instruction, CSRAddresses.mstatus)) {
      mstatus := formatBeforeWritingToMSTATUS(writeBackResult.writeBackData, mstatus)
    }
  }

  // mvendorid, marchid, mimpid is constant and never changed at runtime
  // mhartid is unique per hart and never changed at runtime
}

object DecodeUnit extends App{
  emitVerilog(new decode())
}