package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._

// definition of all ports can be found here
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

// once the rules is finalized, this port will be added to ports.scala
class pushInsToPipeline extends composableInterface {
  // IMPORTANT - value of x0 should never be issued with *.fromRob asserted
  val src1 = Output(new Bundle {                  // {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1}
    val fromRob = Bool()
    val data    = UInt(dataWidth.W)
    val robAddr = UInt(robAddrWidth.W)
  })
  val src2        = Output(src1.cloneType)        // {jalr, jal - 4.U}, {loads, stores, iops*, auipc - immediate}, {rops* - rs2}
  val writeData   = Output(src1.cloneType)
  val instruction = Output(UInt(insAddrWidth.W))
  val pc          = Output(UInt(dataWidth.W))
  val robAddr     = Input(UInt(robAddrWidth.W))   // allocated address in rob
}

// once the rules is finalized, this port will be added to ports.scala
class pullCommitFrmRob extends composableInterface {
  // for now consider that instructions that don't get writeback to register file are not returned back to Decode
  val robAddr       = Input(UInt(robAddrWidth.W))
  val rdAddr        = Input(UInt(rdWidth.W))
  val writeBackData = Input(UInt(dataWidth.W))
}

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
  val fromFetch       = IO(new recivInstrFrmFetch)        // receives instructions from fetch and communicates the pc of the expected instruction
  val branchRes       = IO(new branchResFrmDecode)        // sends results of branched to fetch unit
  val toExec          = IO(new pushInsToPipeline)         // sends the decoded instruction to the next stage of the pipeline
  val writeBackResult = IO(new pullCommitFrmRob)          // receives results to write into the register file

  /**
    * Internal of the module goes here
    */
//----------------------------------------------------------------------------------------------------------------------//

  // Assigning some wires for inputs to the decode unit
  val validIn       = fromFetch.fired                         // Valid signal from the fetch unit
  val writeEn       = writeBackResult.fired                   // Write enable signal to the register file from the rob
  val writeBackData = writeBackResult.writeBackData           // Writeback data to the register file
  val writeRd       = Wire(UInt(rdWidth.W))                   // Writeback address of the register file
  writeRd          := writeBackResult.rdAddr
  val readyIn       = toExec.fired                            // Valid signal from the exec unit

  // Initializing a buffer for storing the input values from the fetch unit
  val fetchIssueBuffer = RegInit(new Bundle{
    val pc          = UInt(dataWidth.W)
    val instruction = UInt(insAddrWidth.W)
  }.Lit(
    _.pc          -> initialPC.U,                         // Initial value is set for the expectedPC
    _.instruction -> 0.U
  ))

  // Initializing a buffer for storing the output values to the exec unit
  val decodeIssueBuffer = RegInit(new Bundle{
    val instruction  = UInt(insAddrWidth.W)
    val pc           = UInt(dataWidth.W)
    val insType      = UInt(3.W)                           // RISC-V instruction type: {R-type, I-type, S-type, B-type, U-type, J-type, N-type(for fence)}
    val rs1Data      = UInt(dataWidth.W)                   // Data of register source 1
    val rs1RobAddr   = UInt(robAddrWidth.W)                // ROB address of register source 1
    val rs1FromRob   = Bool()                              // Where the source 1 data can be found : (true -> ROB, false -> register file)
    val rs2Data      = UInt(dataWidth.W)                   // Data of register source 2 or immediates
    val rs2RobAddr   = UInt(robAddrWidth.W)                // ROB address of register source 2
    val rs2FromRob   = Bool()                              // Where the source 2 data can be found : (true -> ROB, false -> register file)
    val writeData    = UInt(dataWidth.W)                   // Data of register source 2 for store instructions
    val writeRobAddr = UInt(robAddrWidth.W)                // ROB address of register source 2 for store instrucitons
    val writeFromRob = Bool()                              // Where the source 2 data for the store instrucitons can be found : (true -> ROB, false -> register file)
  }.Lit(
    _.instruction  -> 0.U,
    _.pc           -> 0.U,
    _.insType      -> 0.U,
    _.rs1Data      -> 0.U,
    _.rs1RobAddr   -> 0.U,
    _.rs1FromRob   -> false.B,
    _.rs2Data      -> 0.U,
    _.rs2RobAddr   -> 0.U,
    _.rs2FromRob   -> false.B,
    _.writeData    -> 0.U,
    _.writeRobAddr -> 0.U,
    _.writeFromRob -> false.B,
  ))

  // Initializing some intermediate wires
  val opcode = WireDefault(0.U(opcodeWidth.W))
  val rs1    = WireDefault(0.U(rs1Width.W))
  val rs2    = WireDefault(0.U(rs2Width.W))
  val rd     = WireDefault(0.U(rdWidth.W))

  val validOutFetchBuf = WireDefault(false.B)         // Valid signal of fetch buffer
  val readyOutFetchBuf = WireDefault(false.B)         // Ready signal of fetch buffer

  val validOutDecodeBuf = WireDefault(false.B)        // Valid signal of decode buffer
  val readyOutDecodeBuf = WireDefault(false.B)        // Ready signal of decode buffer

  val insType        = WireDefault(0.U(3.W))
  val immediate      = WireDefault(0.U(dataWidth.W))
  val rs1Data        = WireDefault(0.U(dataWidth.W))
  val rs1RobAddr     = WireDefault(0.U(robAddrWidth.W))
  val rs1FromRob     = WireDefault(false.B)
  val rs2Data        = WireDefault(0.U(dataWidth.W))
  val rs2RobAddr     = WireDefault(0.U(robAddrWidth.W))
  val rs2FromRob     = WireDefault(false.B)
  val writeData      = WireDefault(0.U(dataWidth.W))
  val writeRobAddr   = WireDefault(0.U(robAddrWidth.W))
  val writeFromRob   = WireDefault(false.B)
  val rdValid        = WireDefault(true.B)                  // Destination register valid signal
  val rs1DataValid   = WireDefault(true.B)                  // rs1 register data valid signal
  val rs1RobValid    = WireDefault(false.B)                 // rs1 register rob address valid signal
  val rs2DataValid   = WireDefault(true.B)                  // rs2 register valid signal
  val rs2RobValid    = WireDefault(false.B)                 // rs2 register rob address valid signal
  val writeDataValid = WireDefault(true.B)                  // rs2 register valid signal for store instructions
  val writeRobValid  = WireDefault(false.B)                 // rs2 register rob address valid signal for store instructions
  val stalled        = WireDefault(false.B)                 // Stall signal

  val ins = WireDefault(0.U(insAddrWidth.W))
  val pc  = WireDefault(0.U(dataWidth.W))

  val isBranch   = WireDefault(false.B)               // Current instruction is a branch or not
  val expectedPC = WireDefault(0.U(dataWidth.W))      // Expected pc value from the decode unit to the fetch unit

  val branchValid   = WireDefault(false.B)            // Branch result port signals are valid or not
  val branchIsTaken = WireDefault(false.B)            // Branch is taken or not
  val branchPC      = WireDefault(0.U(dataWidth.W))   // Address of the branch instruction
  val branchTarget  = WireDefault(0.U(dataWidth.W))   // Next address of the instruction after the branch

  // Initializing states for the FSMs for fetch buffer and decode buffer
  val emptyState :: fullState :: Nil = Enum(2)        // States of FSM
  val stateRegFetchBuf  = RegInit(emptyState)
  val stateRegDecodeBuf = RegInit(emptyState)

  // Storing instruction and pc in the fetch buffer
  when(validIn && readyOutFetchBuf && fromFetch.expected.pc === fromFetch.pc) {       // Data from the fetch unit is valid and fetch buffer is ready and the expected PC is matching
    fetchIssueBuffer.instruction := fromFetch.instruction
    fetchIssueBuffer.pc          := fromFetch.pc
  }

  ins := fetchIssueBuffer.instruction
  pc  := fetchIssueBuffer.pc

  opcode := ins(6,0)
  rs1    := ins(19,15)
  rs2    := ins(24,20)
  rd     := ins(11,7)

  // Storing values to the decode buffer
  when(validOutFetchBuf && readyOutDecodeBuf) {             // data from the fetch buffer is valid and decode buffer is ready
    decodeIssueBuffer.instruction  := ins
    decodeIssueBuffer.pc           := pc
    decodeIssueBuffer.insType      := insType
    decodeIssueBuffer.rs1Data      := rs1Data
    decodeIssueBuffer.rs1RobAddr   := rs1RobAddr
    decodeIssueBuffer.rs1FromRob   := rs1FromRob
    decodeIssueBuffer.rs2Data      := rs2Data
    decodeIssueBuffer.rs2RobAddr   := rs2RobAddr
    decodeIssueBuffer.rs2FromRob   := rs2FromRob
    decodeIssueBuffer.writeData    := writeData
    decodeIssueBuffer.writeRobAddr := writeRobAddr
    decodeIssueBuffer.writeFromRob := writeFromRob
  }

  // Assigning outputs
  toExec.ready             := validOutDecodeBuf
  toExec.instruction       := decodeIssueBuffer.instruction
  toExec.pc                := decodeIssueBuffer.pc
  toExec.src1.data         := decodeIssueBuffer.rs1Data
  toExec.src1.robAddr      := decodeIssueBuffer.rs1RobAddr
  toExec.src1.fromRob      := decodeIssueBuffer.rs1FromRob
  toExec.src2.data         := decodeIssueBuffer.rs2Data
  toExec.src2.robAddr      := decodeIssueBuffer.rs2RobAddr
  toExec.src2.fromRob      := decodeIssueBuffer.rs2FromRob
  toExec.writeData.data    := decodeIssueBuffer.writeData
  toExec.writeData.robAddr := decodeIssueBuffer.writeRobAddr
  toExec.writeData.fromRob := decodeIssueBuffer.writeFromRob

  fromFetch.ready          := readyOutFetchBuf
  fromFetch.expected.pc    := expectedPC
  fromFetch.expected.valid := readyOutFetchBuf

  branchRes.ready         := true.B
  branchRes.isBranch      := isBranch
  branchRes.branchTaken   := branchIsTaken
  branchRes.pc            := branchPC
  branchRes.pcAfterBrnach := branchTarget

  writeBackResult.ready := true.B

  // Deciding the instruction type
  //--------------------------------------------------------------------------------------------------------------------//
  switch(opcode) {
    is(lui.U) {
      insType := utype.U
    }
    is(auipc.U) {
      insType := utype.U
    }
    is(jump.U) {
      insType := jtype.U
    }
    is(jumpr.U) {
      insType := itype.U
    }
    is(cjump.U) {
      insType := btype.U
    }
    is(load.U) {
      insType := itype.U
    }
    is(store.U) {
      insType := stype.U
    }
    is(iops.U) {
      insType := itype.U
    }
    is(iops32.U) {
      insType := itype.U
    }
    is(rops.U) {
      insType := rtype.U
    }
    is(rops32.U) {
      insType := rtype.U
    }
    is(system.U) {
      insType := itype.U
    }
    is(fence.U) {
      insType := ntype.U
    }
    is(amos.U) {
      insType := rtype.U
    }
  }
  //--------------------------------------------------------------------------------------------------------------------//

  // Calculating the immediate value
  //--------------------------------------------------------------------------------------------------------------------//
  switch(insType) {
    is(itype.U) {
      immediate := Cat(Fill(53, ins(31)), ins(30, 20))
    }
    is(stype.U) {
      immediate := Cat(Fill(53, ins(31)), ins(30, 25), ins(11, 7))
    }
    is(btype.U) {
      immediate := Cat(Fill(53, ins(31)), ins(7), ins(30, 25), ins(11, 8), 0.U(1.W))
    }
    is(utype.U) {
      immediate := Cat(Fill(32, ins(31)), ins(31, 12), 0.U(12.W))
    }
    is(jtype.U) {
      immediate := Cat(Fill(44, ins(31)), ins(19, 12), ins(20), ins(30, 25), ins(24, 21), 0.U(1.W))
    }
    is(ntype.U) {
      immediate := Fill(dataWidth, 0.U)
    }
    is(rtype.U) {
      immediate := Fill(dataWidth, 0.U)
    }
  }
  //--------------------------------------------------------------------------------------------------------------------//

  // Branch detection
  isBranch := opcode === jump.U || opcode === jumpr.U || opcode === cjump.U

  // Register File activities
  //--------------------------------------------------------------------------------------------------------------------//

  /*                  Register File
  |---------------------------------------------------|
  | validBit | register data | valdiBit | ROB address |
  |---------------------------------------------------|
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |---------------------------------------------------|
   */

  // Valid bits for each register
  val validBit = RegInit(VecInit(Seq.fill(regCount)(1.U(1.W))))
  validBit(0) := 1.U

  // Initializing the Register file
  val registerFile = Reg(Vec(regCount, UInt(dataWidth.W)))
  registerFile(0) := 0.U

  // Valid bits for each ROB address of registers
  val robValidBit = RegInit(VecInit(Seq.fill(regCount)(0.U(1.W))))
  robValidBit(0) := 0.U

  // Initializing the ROB address table of register file
  val robFile = Reg(Vec(regCount, UInt(robAddrWidth.W)))

  // Setting rs1 properties
  when(opcode === auipc.U || opcode === jump.U || opcode === jumpr.U) {
    rs1Data := pc
    rs1DataValid := true.B
    rs1RobAddr := 0.U
    rs1RobValid := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === rops.U || opcode === iops.U || opcode === rops32.U || opcode === iops32.U || opcode === cjump.U) {
    rs1Data := registerFile(rs1)
    when(validBit(rs1) === 1.U) {
      rs1DataValid := true.B
    } otherwise {
      rs1DataValid := false.B
    }
    rs1RobAddr := robFile(rs1)
    when(robValidBit(rs1) === 1.U) {
      rs1RobValid := true.B
    } otherwise {
      rs1RobValid := false.B
    }
  }

  // Setting rs2 properties
  when(opcode === jumpr.U || opcode === jump.U) {
    rs2Data := 4.U
    rs2DataValid := true.B
    rs2RobAddr := 0.U
    rs2RobValid := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === iops.U || opcode === iops32.U || opcode === auipc.U) {
    rs2Data := immediate
    rs2DataValid := true.B
    rs2RobAddr := 0.U
    rs2RobValid := false.B
  }
  when(opcode === cjump.U || opcode === rops.U || opcode === rops32.U) {
    rs2Data := registerFile(rs2)
    when(validBit(rs2) === 1.U) {
      rs2DataValid := true.B
    } otherwise {
      rs2DataValid := false.B
    }
    rs2RobAddr := robFile(rs2)
    when(robValidBit(rs2) === 1.U) {
      rs2RobValid := true.B
    } otherwise {
      rs2RobValid := false.B
    }
  }

  // Setting rs2 properties for store instructions
  when(opcode === store.U) {
    writeData := registerFile(rs2)
    when(validBit(rs2) === 1.U) {
      writeDataValid := true.B
    } otherwise {
      writeDataValid := false.B
    }
    writeRobAddr := robFile(rs2)
    when(robValidBit(rs2) === 1.U) {
      writeRobValid := true.B
    } otherwise {
      writeRobValid := false.B
    }
  }

  // Register writing
  when(writeEn === 1.U && validBit(writeRd) === 0.U && writeRd =/= 0.U) {
    registerFile(writeRd)  := writeBackData
    when(robFile(writeRd) === writeBackResult.robAddr) {
      validBit(writeRd)    := 1.U
      robValidBit(writeRd) := 0.U
    }
  }

  // Rob File writing
  when(decodeIssueBuffer.insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) {
    when(readyIn) {
      robFile(decodeIssueBuffer.instruction(rd)) := toExec.robAddr
      robValidBit(decodeIssueBuffer.instruction(rd)) := 1.U
    }
  }

  // Checking rs1 validity
  when(insType === rtype.U || insType === itype.U || insType === stype.U || insType === btype.U) {
    when(validBit(rs1) === 0.U) {
      rs1DataValid := false.B
    }
    when(robValidBit(rs1) === 1.U) {
      rs1RobValid := true.B
    }
  }
  // Checking rs2 validity
  when(insType === rtype.U || insType === stype.U || insType === btype.U) {
    when(validBit(rs2) === 0.U) {
      rs2DataValid := false.B
    }
    when(robValidBit(rs2) === 1.U) {
      rs2RobValid := true.B
    }
  }
  // Checking rd validity and changing the valid bit for rd
  when(insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) {
    when(validBit(rd) === 0.U) {
      rdValid := false.B
    }
      .otherwise {
        when(validOutFetchBuf && readyIn && rd =/= 0.U) {
          validBit(rd) := 0.U
        }
      }
  }
  //--------------------------------------------------------------------------------------------------------------------//

  when(stateRegFetchBuf === fullState) {
    stalled      := !((rs1DataValid || rs1RobValid) && (rs2RobValid || rs2DataValid) && (writeDataValid || writeRobValid)) || (isBranch && !(rs1DataValid && rs2DataValid)) // stall signal for FSM
    rs1FromRob   := !rs1DataValid && rs1RobValid
    rs2FromRob   := !rs2DataValid && rs2RobValid
    writeFromRob := !writeDataValid && writeRobValid
  }

  // FSM for ready valid interface of fetch buffer
  // -------------------------------------------------------------------------------------------------------------------//
  switch(stateRegFetchBuf) {
    is(emptyState) {
      validOutFetchBuf := false.B
      readyOutFetchBuf := true.B
      when(validIn && fromFetch.pc === fromFetch.expected.pc) {
        stateRegFetchBuf := fullState
      }
    }
    is(fullState) {
      when(stalled) {
        validOutFetchBuf := false.B
        readyOutFetchBuf := false.B
      } otherwise {
        validOutFetchBuf := true.B
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
  // -------------------------------------------------------------------------------------------------------------------//

  // FSM for ready valid interface of decode buffer
  // -------------------------------------------------------------------------------------------------------------------//
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
  // -------------------------------------------------------------------------------------------------------------------//

  // Branch handling
  //  ------------------------------------------------------------------------------------------------------------------//
  def getResult(pattern: (chisel3.util.BitPat, chisel3.UInt), prev: UInt) = pattern match {
    case (bitpat, result) => Mux(ins === bitpat, result, prev)
  }

  when(isBranch && !stalled) {
    val branchTaken = Seq(
      rs1Data === rs2Data,
      rs1Data =/= rs2Data,
      rs1Data.asSInt < rs2Data.asSInt,
      rs1Data.asSInt >= rs2Data.asSInt,
      rs1Data < rs2Data,
      rs1Data >= rs2Data
    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === ins(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _)

    val branchNextAddress = Mux(branchTaken, pc + immediate, pc + 4.U)

    val target = Seq(
      BitPat("b?????????????????????????1101111") -> (pc + immediate), // jal
      BitPat("b?????????????????????????1100111") -> (rs1Data + immediate), //jalr
      BitPat("b?????????????????????????1100011") -> branchNextAddress, // branches
    ).foldRight(pc + 4.U)(getResult)

    expectedPC := target
    when(stateRegFetchBuf === fullState) {
      branchValid := true.B
    }
    branchIsTaken := branchTaken
    branchPC := pc
    branchTarget := target
  } otherwise {
    expectedPC := pc + 4.U
  }
  //--------------------------------------------------------------------------------------------------------------------//
}

//commented out because new additions can cause issues with building other codes
object DecodeUnit extends App{
  emitVerilog(new decode())
}
