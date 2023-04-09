package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._
import pipeline.decode.utils._

/** definition of all ports can be found here */
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

/**
  * Functionality - Must communicate the pc of the first instruction to execute
  * through from Fetch.
  * 
  * Details about the IO can be found on common/ports.scala
  *
  */
class decode extends Module {
   /**
    * Inputs and Outputs of the module
    */
  val fromFetch       = IO(new recivInstrFrmFetch)        /** receives instructions from fetch and communicates the pc of the expected instruction */
  val branchRes       = IO(new branchResFrmDecode)        /** sends results of branched to fetch unit */
  val toExec          = IO(new pushInsToPipeline)         /** sends the decoded instruction to the next stage of the pipeline */
  val writeBackResult = IO(new pullCommitFrmRob)          /** receives results to write into the register file */

  /**
    * Internal of the module goes here
    */
/**----------------------------------------------------------------------------------------------------------------------*/

  /** Assigning some wires for inputs to the decode unit */
  val validIn       = fromFetch.fired                         /** Valid signal from the fetch unit */
  val writeEn       = writeBackResult.fired                   /** Write enable signal to the register file from the rob */
  val writeBackData = writeBackResult.writeBackData           /** Writeback data to the register file */
  val writeRd       = Wire(UInt(rdWidth.W))                   /** Writeback address of the register file */
  writeRd          := writeBackResult.rdAddr
  val readyIn       = toExec.fired                            /** Valid signal from the exec unit */

  /** Initializing a buffer for storing the input values from the fetch unit */
  val fetchIssueBuffer = RegInit(new Bundle{
    val pc          = UInt(dataWidth.W)
    val instruction = UInt(insAddrWidth.W)
  }.Lit(
    _.pc          -> initialPC.U,                         /** Initial value is set for the expectedPC */
    _.instruction -> 0.U
  ))

  /** Initializing a buffer for storing the output values to the exec unit */
  val decodeIssueBuffer = RegInit(new Bundle{
    val instruction = UInt(insAddrWidth.W)
    val pc          = UInt(dataWidth.W)
    val insType     = UInt(3.W)                           /** RISC-V instruction type: {R-type, I-type, S-type, B-type, U-type, J-type, N-type(for fence)} */
    val rs1         = new Src                             /** Register source 1 */
    val rs2         = rs1.cloneType                       /** Register source 2 */
    val write       = rs1.cloneType                       /** Register source 2 for store instructions */
  }.Lit(
    _.instruction   -> 0.U,
    _.pc            -> 0.U,
    _.insType       -> 0.U,
    _.rs1.data      -> 0.U,
    _.rs1.robAddr   -> 0.U,
    _.rs1.fromRob   -> false.B,
    _.rs2.data      -> 0.U,
    _.rs2.robAddr   -> 0.U,
    _.rs2.fromRob   -> false.B,
    _.write.data    -> 0.U,
    _.write.robAddr -> 0.U,
    _.write.fromRob -> false.B,
  ))

  /** Initializing some intermediate wires */
  val opcode  = WireDefault(0.U(opcodeWidth.W))
  val rs1Addr = WireDefault(0.U(rs1Width.W))
  val rs2Addr = WireDefault(0.U(rs2Width.W))
  val rdAddr  = WireDefault(0.U(rdWidth.W))
  val fun3    = WireDefault(0.U(3.W))

  val validOutFetchBuf = WireDefault(false.B)         /** Valid signal of fetch buffer */
  val readyOutFetchBuf = WireDefault(false.B)         /** Ready signal of fetch buffer */

  val validOutDecodeBuf = WireDefault(false.B)        /** Valid signal of decode buffer */
  val readyOutDecodeBuf = WireDefault(false.B)        /** Ready signal of decode buffer */

  val insType   = WireDefault(0.U(3.W))
  val immediate = WireDefault(0.U(dataWidth.W))
  val rs1 = WireDefault(new Src().Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  val rs2 = WireDefault(rs1.cloneType.Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  val write = WireDefault(rs1.cloneType.Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  
  val valid = WireDefault(new Validity().Lit(
    _.rs1Data      -> true.B,
    _.rs1RobAddr   -> false.B,
    _.rs2Data      -> true.B,
    _.rs2RobAddr   -> false.B,
    _.writeData    -> true.B,
    _.writeRobAddr -> false.B
  ))

  val stalled        = WireDefault(false.B)                 /** Stall signal */

  val ins = WireDefault(0.U(insAddrWidth.W))
  val pc  = WireDefault(0.U(dataWidth.W))

  val expectedPC = WireDefault(0.U(dataWidth.W))      /** Expected pc value from the decode unit to the fetch unit */
  val branch = WireDefault(new Branch().Lit(
    _.isBranch      -> false.B,
    _.branchTaken   -> false.B,
    _.pc            -> 0.U,
    _.pcAfterBrnach -> 0.U
  ))
  val branchValid   = WireDefault(false.B)            /** Branch result port signals are valid or not */

  val isCSR        = WireDefault(false.B)
  val waitToCommit = WireDefault(false.B)
  val csrDone      = RegInit(false.B)
  val issueRobBuff = RegInit(0.U(robAddrWidth.W))
  val commitRobBuf = RegInit(0.U(robAddrWidth.W))

  /** Initializing states for the FSMs for fetch buffer and decode buffer */
  val emptyState :: fullState :: Nil = Enum(2)        /** States of FSM */
  val stateRegFetchBuf  = RegInit(emptyState)
  val stateRegDecodeBuf = RegInit(emptyState)

  /** Storing instruction and pc in the fetch buffer */
  when(validIn && readyOutFetchBuf && fromFetch.expected.pc === fromFetch.pc) {       /** Data from the fetch unit is valid and fetch buffer is ready and the expected PC is matching */
    fetchIssueBuffer.instruction := fromFetch.instruction
    fetchIssueBuffer.pc          := fromFetch.pc
  }

  /** Storing values to the decode buffer */
  when(validOutFetchBuf && readyOutDecodeBuf) {             /** data from the fetch buffer is valid and decode buffer is ready */
    decodeIssueBuffer.instruction := ins
    decodeIssueBuffer.pc          := pc
    decodeIssueBuffer.insType     := insType
    decodeIssueBuffer.rs1         := rs1
    decodeIssueBuffer.rs2         := rs2
    decodeIssueBuffer.write       := write
  }

  /** Assigning outputs */
  /**--------------------------------------------------------------------------------------------------------------------*/
  toExec.ready       := validOutDecodeBuf
  toExec.instruction := decodeIssueBuffer.instruction
  toExec.pc          := decodeIssueBuffer.pc
  toExec.src1        := decodeIssueBuffer.rs1
  toExec.src2        := decodeIssueBuffer.rs2
  toExec.writeData   := decodeIssueBuffer.write

  fromFetch.ready          := readyOutFetchBuf
  fromFetch.expected.pc    := RegNext(expectedPC)
  fromFetch.expected.valid := RegNext(!((stateRegFetchBuf === fullState && fetchIssueBuffer.instruction(6, 2) === BitPat("b110??")) || (stateRegDecodeBuf === fullState && (decodeIssueBuffer.instruction(6, 2) === BitPat("b110??") || toExec.fired))))

  branchRes.ready         := true.B                   /** branchValid */
  branchRes.isBranch      := branch.isBranch
  branchRes.branchTaken   := branch.branchTaken
  branchRes.pc            := branch.pc
  branchRes.pcAfterBrnach := branch.pcAfterBrnach

  writeBackResult.ready := true.B
  /**--------------------------------------------------------------------------------------------------------------------*/

  ins := fetchIssueBuffer.instruction
  pc  := fetchIssueBuffer.pc

  opcode  := ins(6, 0)
  rs1Addr := ins(19, 15)
  rs2Addr := ins(24, 20)
  rdAddr  := ins(11, 7)
  fun3    := ins(14, 12)

  insType         := getInsType(opcode)                                                 /** Deciding the instruction type */
  immediate       := getImmediate(ins, insType)                                         /** Calculating the immediate value */
  branch.isBranch := opcode === jump.U || opcode === jumpr.U || opcode === cjump.U      /** Branch detection */

  /** Register File activities */
  /**--------------------------------------------------------------------------------------------------------------------*/

  /**                  Register File
  |---------------------------------------------------|
  | validBit | register data | valdiBit | ROB address |
  |---------------------------------------------------|
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |---------------------------------------------------|
   */

  /** Valid bits for each register */
  val validBit = RegInit(VecInit(Seq.fill(regCount)(1.U(1.W))))
  validBit(0) := 1.U

  /** Initializing the Register file */
  val registerFile = Reg(Vec(regCount, UInt(dataWidth.W)))
  registerFile(0) := 0.U

  /** Valid bits for each ROB address of registers */
  val robValidBit = RegInit(VecInit(Seq.fill(regCount)(0.U(1.W))))
  robValidBit(0) := 0.U

  /** Initializing the ROB address table of register file */
  val robFile = Reg(Vec(regCount, UInt(robAddrWidth.W)))

  /** Setting rs1 properties */
  when(opcode === auipc.U || opcode === jump.U || opcode === jumpr.U) {
    rs1.data         := pc
    rs1.robAddr      := 0.U
    valid.rs1Data    := true.B
    valid.rs1RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === rops.U || opcode === iops.U || opcode === rops32.U || opcode === iops32.U || opcode === cjump.U) {
    rs1.data         := registerFile(rs1Addr)
    rs1.robAddr      := robFile(rs1Addr)
    valid.rs1Data    := Mux(validBit(rs1Addr) === 1.U, true.B, false.B)
    valid.rs1RobAddr := Mux(robValidBit(rs1Addr) === 1.U, true.B, false.B)
    when(toExec.fired && decodeIssueBuffer.instruction(11, 7) === rs1Addr && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs1Addr =/= 0.U) {
      valid.rs1Data := false.B
      valid.rs1RobAddr := true.B
      rs1.robAddr := toExec.robAddr
      rs1.fromRob := true.B
    }/* .elsewhen(writeBackResult.fired && (robFile(writeBackResult.rdAddr) === writeBackResult.robAddr) && (writeBackResult.rdAddr === rs1Addr) && (writeBackResult.rdAddr =/= 0.U) && (writeBackResult.rdAddr =/= 0.U)) {
      rs1.data         := writeBackResult.writeBackData
      valid.rs1Data    := true.B
      valid.rs1RobAddr := false.B
    } */
  }
  when(writeBackResult.fired && (robFile(writeBackResult.rdAddr) === writeBackResult.robAddr) && toExec.ready && !toExec.fired && writeBackResult.rdAddr =/= 0.U && (writeBackResult.rdAddr =/= 0.U)) {
    when((writeBackResult.rdAddr === decodeIssueBuffer.instruction(19, 15)) && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === stype.U || decodeIssueBuffer.insType === btype.U)) {
      decodeIssueBuffer.rs1.data         := writeBackResult.writeBackData
      decodeIssueBuffer.rs1.fromRob    := false.B
    }
    when((writeBackResult.rdAddr === decodeIssueBuffer.instruction(24, 20)) && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === stype.U || decodeIssueBuffer.insType === btype.U)) {
      
      when(decodeIssueBuffer.insType === stype.U) {
        decodeIssueBuffer.write.data         := writeBackResult.writeBackData
        decodeIssueBuffer.write.fromRob    := false.B
      }/* .otherwise {
        decodeIssueBuffer.rs2.data         := writeBackResult.writeBackData
        decodeIssueBuffer.rs2.fromRob    := false.B
      } */
    }
  }

  /** Setting rs2 properties */
  when(opcode === jumpr.U || opcode === jump.U) {
    rs2.data         := 4.U
    rs2.robAddr      := 0.U
    valid.rs2Data    := true.B
    valid.rs2RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === iops.U || opcode === iops32.U || opcode === auipc.U || opcode === lui.U) {
    rs2.data := immediate
    rs2.robAddr      := 0.U
    valid.rs2Data    := true.B
    valid.rs2RobAddr := false.B
  }
  when(opcode === cjump.U || opcode === rops.U || opcode === rops32.U) {
    rs2.data         := registerFile(rs2Addr)
    rs2.robAddr      := robFile(rs2Addr)
    valid.rs2Data    := Mux(validBit(rs2Addr) === 1.U, true.B, false.B)
    valid.rs2RobAddr := Mux(robValidBit(rs2Addr) === 1.U, true.B, false.B)
    when(toExec.fired && decodeIssueBuffer.instruction(11, 7) === rs2Addr && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs2Addr =/= 0.U) {
      valid.rs2Data := false.B
      valid.rs2RobAddr := true.B
      rs2.robAddr := toExec.robAddr
    }/* .elsewhen(writeBackResult.fired && (robFile(writeBackResult.rdAddr) === writeBackResult.robAddr) && (writeBackResult.rdAddr === rs2Addr)) {
      rs2.data         := writeBackResult.writeBackData
      valid.rs2Data    := true.B
      valid.rs2RobAddr := false.B
    } */
  }

  /** Setting rs2 properties for store instructions */
  when(opcode === store.U) {
    write.data         := registerFile(rs2Addr)
    write.robAddr      := robFile(rs2Addr)
    valid.writeData    := Mux(validBit(rs2Addr) === 1.U, true.B, false.B)
    valid.writeRobAddr := Mux(robValidBit(rs2Addr) === 1.U, true.B, false.B)
    when(toExec.fired && decodeIssueBuffer.instruction(11, 7) === rs2Addr && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs2Addr =/= 0.U) {
      valid.writeData := false.B
      valid.writeRobAddr := true.B
      write.robAddr := toExec.robAddr
    }
  }

  /** Register writing */
  when(writeEn === 1.U && validBit(writeRd) === 0.U && writeRd =/= 0.U) {
    registerFile(writeRd)  := writeBackData
    when(robFile(writeRd) === writeBackResult.robAddr && 
      !((insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) && (validOutFetchBuf && RegNext(fromFetch.fired) && rdAddr =/= 0.U) && rdAddr === writeRd)) {// &&
      //!((decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && (validOutDecodeBuf && decodeIssueBuffer.instruction(11, 7) =/= 0.U) && decodeIssueBuffer.instruction(11, 7) === writeRd)) {
      validBit(writeRd)    := 1.U
      robValidBit(writeRd) := 0.U
    }
    commitRobBuf           := writeBackResult.robAddr
  }

  /** Rob File writing */
  when(decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) {
    when(readyIn) {
      robFile(decodeIssueBuffer.instruction(11, 7))     := toExec.robAddr
      robValidBit(decodeIssueBuffer.instruction(11, 7)) := 1.U
      issueRobBuff                                       := toExec.robAddr
      validBit(decodeIssueBuffer.instruction(11, 7))  := 0.U
    }
  }

  /** Checking rs1 validity */
  when(insType === rtype.U || insType === itype.U || insType === stype.U || insType === btype.U) {
    valid.rs1Data    := Mux(validBit(rs1Addr) === 0.U, false.B, true.B)
    valid.rs1RobAddr := Mux(robValidBit(rs1Addr) === 1.U, true.B, false.B)
  }
  /** Checking rs2 validity */
  when(insType === rtype.U || insType === stype.U || insType === btype.U) {
    valid.rs2Data    := Mux(validBit(rs2Addr) === 0.U, false.B, true.B)
    valid.rs2RobAddr := Mux(robValidBit(rs2Addr) === 1.U, true.B, false.B)
  }
  /** Checking rd validity and changing the valid bit for rd */
  when(insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) {
    when(validBit(rdAddr) === 0.U) {
//      rdValid := false.B
    }
      .otherwise {
        when(validOutFetchBuf && RegNext(fromFetch.fired) && rdAddr =/= 0.U) {
          //validBit(rdAddr) := 0.U
        }
      }
  }
  /**--------------------------------------------------------------------------------------------------------------------*/

  when(stateRegFetchBuf === fullState && !isCSR) {
    stalled       := !((valid.rs1Data || valid.rs1RobAddr) && (valid.rs2RobAddr || valid.rs2Data) && (valid.writeData || valid.writeRobAddr)) || (branch.isBranch && !(valid.rs1Data && valid.rs2Data)) /** stall signal for FSM */
    rs1.fromRob   := !valid.rs1Data && valid.rs1RobAddr || (toExec.fired && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs1Addr === decodeIssueBuffer.instruction(11, 7) && rs1Addr =/= 0.U && (insType === rtype.U || insType === itype.U || insType === stype.U))
    rs2.fromRob   := !valid.rs2Data && valid.rs2RobAddr || (toExec.fired && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs2Addr === decodeIssueBuffer.instruction(11, 7) && rs2Addr =/= 0.U && (insType === rtype.U))
    write.fromRob := !valid.writeData && valid.writeRobAddr || (toExec.fired && (decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && rs2Addr === decodeIssueBuffer.instruction(11, 7) && rs2Addr =/= 0.U && (insType === stype.U))
  }

  /** FSM for ready valid interface of fetch buffer */
  /** -------------------------------------------------------------------------------------------------------------------*/
  switch(stateRegFetchBuf) {
    is(emptyState) {
      validOutFetchBuf := false.B
      readyOutFetchBuf := true.B
      when(validIn && fromFetch.pc === fromFetch.expected.pc) {
        stateRegFetchBuf := fullState
      }
    }
    is(fullState) {
      when(stalled || (isCSR && !csrDone)) {
        validOutFetchBuf := false.B
        readyOutFetchBuf := false.B
      } otherwise {
        validOutFetchBuf := Mux(csrDone, false.B, true.B)
        when(readyOutDecodeBuf) {
          readyOutFetchBuf := true.B
          when(!validIn || fromFetch.pc =/= fromFetch.expected.pc) {
            stateRegFetchBuf := emptyState
          }
        } otherwise {
          readyOutFetchBuf := false.B
        }
      }
    }
  }
  /** -------------------------------------------------------------------------------------------------------------------*/

  /** FSM for ready valid interface of decode buffer */
  /** -------------------------------------------------------------------------------------------------------------------*/
  switch(stateRegDecodeBuf) {
    is(emptyState) {
      validOutDecodeBuf := false.B
      readyOutDecodeBuf := true.B
      when(validOutFetchBuf) {
        stateRegDecodeBuf := fullState
      }
    }
    is(fullState) {
      validOutDecodeBuf := true.B
      when(readyIn) {
        readyOutDecodeBuf := true.B
        when(!validOutFetchBuf) {
          stateRegDecodeBuf := emptyState
        }
      } otherwise {
        readyOutDecodeBuf := false.B
      }
    }
  }
  /** -------------------------------------------------------------------------------------------------------------------*/

  /** Branch handling */
  /**  ------------------------------------------------------------------------------------------------------------------*/
  def getResult(pattern: (BitPat, UInt), prev: UInt) = pattern match {
    case (bitpat, result) => Mux(ins === bitpat, result, prev)
  }

  when(branch.isBranch && !stalled) {
    val rs1 = decodeIssueBuffer.rs1
    val rs2 = decodeIssueBuffer.rs2
    val ins = decodeIssueBuffer.instruction
    val branchTaken = Seq(
      rs1.data === rs2.data,
      rs1.data =/= rs2.data,
      rs1.data.asSInt < rs2.data.asSInt,
      rs1.data.asSInt >= rs2.data.asSInt,
      rs1.data < rs2.data,
      rs1.data >= rs2.data
    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === ins(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _)

    val pc = RegNext(fetchIssueBuffer.pc)
    val immediate = getImmediate(ins, decodeIssueBuffer.insType)
    val branchNextAddress = Mux(branchTaken, pc + immediate, pc + 4.U)

    val target = Seq(
      BitPat("b?????????????????????????1101111") -> (pc + immediate), /** jal */
      BitPat("b?????????????????????????1100111") -> (rs1.data + immediate), /** jalr */
      BitPat("b?????????????????????????1100011") -> branchNextAddress, /** branches */
    ).foldRight(pc + 4.U)(getResult)

    expectedPC := target
    when(stateRegFetchBuf === fullState) {
      branchValid := true.B
    }
    branch.branchTaken := branchTaken
    branch.pc := pc
    branch.pcAfterBrnach := target
  } otherwise {
    expectedPC := pc + 4.U
  }
  /**--------------------------------------------------------------------------------------------------------------------*/

  /** CSR handling */
  /**--------------------------------------------------------------------------------------------------------------------*/
  isCSR := false.B //(opcode === system.U) && (fun3 === "b001".U || fun3 === "b010".U || fun3 === "b011".U || fun3 === "b101".U || fun3 === "b110".U || fun3 === "b111".U)
  waitToCommit := false.B //isCSR && (issueRobBuff =/= commitRobBuf) && !csrDone

  val csrFile = RegInit(VecInit(Seq.fill(csrRegCount)(0.U(64.W))))

  /* when(isCSR && !waitToCommit) {
    val csrReadData  = csrFile(immediate)
    val csrWriteData = registerFile(rs1Addr)
    val csrWriteImmediate = rs1Addr & "h0000_0000_0000_001f".U
    registerFile(writeRd) := csrReadData
    switch(fun3) {
      is("b001".U) {
        csrFile(immediate) := csrWriteData
      }
      is("b010".U) {
        csrFile(immediate) := csrReadData | csrWriteData
      }
      is("b011".U) {
        csrFile(immediate) := csrReadData & (~csrWriteData)
      }
      is("b101".U) {
        csrFile(immediate) := csrWriteImmediate
      }
      is("b110".U) {
        csrFile(immediate) := csrReadData | csrWriteImmediate
      }
      is("b111".U) {
        csrFile(immediate) := csrReadData & (~csrWriteImmediate)
      }
    }
    csrDone := true.B
  } */

  /* when(csrDone && validIn && fromFetch.pc === fromFetch.expected.pc) {
    csrDone := false.B
  } */
  csrDone := false.B


  /**--------------------------------------------------------------------------------------------------------------------*/
}

object DecodeUnit extends App{
  emitVerilog(new decode())
}
