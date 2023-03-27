package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._

// definition of all ports can be found here
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

class Src extends Bundle {
  val fromRob = Bool()                            // ROB address of the source
  val data = UInt(dataWidth.W)                    // Data of the source
  val robAddr = UInt(robAddrWidth.W)              // Where the source data can be found : (true -> ROB, false -> register file)
}

class Validity extends Bundle {                   // Valid signals for Data in the register and ROB Address
  val rs1Data      = Bool()
  val rs1RobAddr   = Bool()
  val rs2Data      = Bool()
  val rs2RobAddr   = Bool()
  val writeData    = Bool()
  val writeRobAddr = Bool()
}

class Branch extends Bundle {                     // Details for branching
  val isBranch      = Output(Bool())
  val branchTaken   = Output(Bool())
  val pc            = Output(UInt(32.W))
  val pcAfterBrnach = Output(UInt(32.W))
}

// once the rules is finalized, this port will be added to ports.scala
class pushInsToPipeline extends composableInterface {
  // IMPORTANT - value of x0 should never be issued with *.fromRob asserted
  val src1        = Output(new Src)               // {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1}
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
    val instruction = UInt(insAddrWidth.W)
    val pc          = UInt(dataWidth.W)
    val insType     = UInt(3.W)                           // RISC-V instruction type: {R-type, I-type, S-type, B-type, U-type, J-type, N-type(for fence)}
    val rs1         = new Src                             // Register source 1
    val rs2         = rs1.cloneType                       // Register source 2
    val write       = rs1.cloneType                       // Register source 2 for store instructions
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

  // Initializing some intermediate wires
  val opcode  = WireDefault(0.U(opcodeWidth.W))
  val rs1Addr = WireDefault(0.U(rs1Width.W))
  val rs2Addr = WireDefault(0.U(rs2Width.W))
  val rdAddr  = WireDefault(0.U(rdWidth.W))

  val validOutFetchBuf = WireDefault(false.B)         // Valid signal of fetch buffer
  val readyOutFetchBuf = WireDefault(false.B)         // Ready signal of fetch buffer

  val validOutDecodeBuf = WireDefault(false.B)        // Valid signal of decode buffer
  val readyOutDecodeBuf = WireDefault(false.B)        // Ready signal of decode buffer

  val insType        = WireDefault(0.U(3.W))
  val immediate      = WireDefault(0.U(dataWidth.W))
  
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

  val stalled        = WireDefault(false.B)                 // Stall signal

  val ins = WireDefault(0.U(insAddrWidth.W))
  val pc  = WireDefault(0.U(dataWidth.W))

  val expectedPC = WireDefault(0.U(dataWidth.W))      // Expected pc value from the decode unit to the fetch unit

  val branch = WireDefault(new Branch().Lit(
    _.isBranch      -> false.B,
    _.branchTaken   -> false.B,
    _.pc            -> 0.U,
    _.pcAfterBrnach -> 0.U
  ))

  val branchValid   = WireDefault(false.B)            // Branch result port signals are valid or not

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

  opcode  := ins(6,0)
  rs1Addr := ins(19,15)
  rs2Addr := ins(24,20)
  rdAddr  := ins(11,7)

  // Storing values to the decode buffer
  when(validOutFetchBuf && readyOutDecodeBuf) {             // data from the fetch buffer is valid and decode buffer is ready
    decodeIssueBuffer.instruction := ins
    decodeIssueBuffer.pc          := pc
    decodeIssueBuffer.insType     := insType
    decodeIssueBuffer.rs1         := rs1
    decodeIssueBuffer.rs2         := rs2
    decodeIssueBuffer.write       := write
  }

  // Assigning outputs
  toExec.ready       := validOutDecodeBuf
  toExec.instruction := decodeIssueBuffer.instruction
  toExec.pc          := decodeIssueBuffer.pc
  toExec.src1        := decodeIssueBuffer.rs1
  toExec.src2        := decodeIssueBuffer.rs2
  toExec.writeData   := decodeIssueBuffer.write

  fromFetch.ready          := readyOutFetchBuf
  fromFetch.expected.pc    := expectedPC
  fromFetch.expected.valid := readyOutFetchBuf

  branchRes.ready         := true.B                   // branchValid
  branchRes.isBranch      := branch.isBranch
  branchRes.branchTaken   := branch.branchTaken
  branchRes.pc            := branch.pc
  branchRes.pcAfterBrnach := branch.pcAfterBrnach

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
  branch.isBranch := opcode === jump.U || opcode === jumpr.U || opcode === cjump.U

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
    rs1.data         := pc
    valid.rs1Data    := true.B
    rs1.robAddr      := 0.U
    valid.rs1RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === rops.U || opcode === iops.U || opcode === rops32.U || opcode === iops32.U || opcode === cjump.U) {
    rs1.data := registerFile(rs1Addr)
    when(validBit(rs1Addr) === 1.U) {
      valid.rs1Data := true.B
    } otherwise {
      valid.rs1Data := false.B
    }
    rs1.robAddr := robFile(rs1Addr)
    when(robValidBit(rs1Addr) === 1.U) {
      valid.rs1RobAddr := true.B
    } otherwise {
      valid.rs1RobAddr := false.B
    }
  }

  // Setting rs2 properties
  when(opcode === jumpr.U || opcode === jump.U) {
    rs2.data         := 4.U
    valid.rs2Data    := true.B
    rs2.robAddr      := 0.U
    valid.rs2RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === iops.U || opcode === iops32.U || opcode === auipc.U) {
    rs2.data := immediate
    valid.rs2Data    := true.B
    rs2.robAddr      := 0.U
    valid.rs2RobAddr := false.B
  }
  when(opcode === cjump.U || opcode === rops.U || opcode === rops32.U) {
    rs2.data := registerFile(rs2Addr)
    when(validBit(rs2Addr) === 1.U) {
      valid.rs2Data := true.B
    } otherwise {
      valid.rs2Data := false.B
    }
    rs2.robAddr := robFile(rs2Addr)
    when(robValidBit(rs2Addr) === 1.U) {
      valid.rs2RobAddr := true.B
    } otherwise {
      valid.rs2RobAddr := false.B
    }
  }

  // Setting rs2 properties for store instructions
  when(opcode === store.U) {
    write.data := registerFile(rs2Addr)
    when(validBit(rs2Addr) === 1.U) {
      valid.writeData := true.B
    } otherwise {
      valid.writeData := false.B
    }
    write.robAddr := robFile(rs2Addr)
    when(robValidBit(rs2Addr) === 1.U) {
      valid.writeRobAddr := true.B
    } otherwise {
      valid.writeRobAddr := false.B
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
      robFile(decodeIssueBuffer.instruction(rdAddr))     := toExec.robAddr
      robValidBit(decodeIssueBuffer.instruction(rdAddr)) := 1.U
    }
  }

  // Checking rs1 validity
  when(insType === rtype.U || insType === itype.U || insType === stype.U || insType === btype.U) {
    when(validBit(rs1Addr) === 0.U) {
      valid.rs1Data := false.B
    }
    when(robValidBit(rs1Addr) === 1.U) {
      valid.rs1RobAddr := true.B
    }
  }
  // Checking rs2 validity
  when(insType === rtype.U || insType === stype.U || insType === btype.U) {
    when(validBit(rs2Addr) === 0.U) {
      valid.rs2Data := false.B
    }
    when(robValidBit(rs2Addr) === 1.U) {
      valid.rs2RobAddr := true.B
    }
  }
  // Checking rd validity and changing the valid bit for rd
  when(insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) {
    when(validBit(rdAddr) === 0.U) {
//      rdValid := false.B
    }
      .otherwise {
        when(validOutFetchBuf && readyIn && rdAddr =/= 0.U) {
          validBit(rdAddr) := 0.U
        }
      }
  }
  //--------------------------------------------------------------------------------------------------------------------//

  when(stateRegFetchBuf === fullState) {
    stalled       := !((valid.rs1Data || valid.rs1RobAddr) && (valid.rs2RobAddr || valid.rs2Data) && (valid.writeData || valid.writeRobAddr)) || (branch.isBranch && !(valid.rs1Data && valid.rs2Data)) // stall signal for FSM
    rs1.fromRob   := !valid.rs1Data && valid.rs1RobAddr
    rs2.fromRob   := !valid.rs2Data && valid.rs2RobAddr
    write.fromRob := !valid.writeData && valid.writeRobAddr
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

  when(branch.isBranch && !stalled) {
    val branchTaken = Seq(
      rs1.data === rs2.data,
      rs1.data =/= rs2.data,
      rs1.data.asSInt < rs2.data.asSInt,
      rs1.data.asSInt >= rs2.data.asSInt,
      rs1.data < rs2.data,
      rs1.data >= rs2.data
    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === ins(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _)

    val branchNextAddress = Mux(branchTaken, pc + immediate, pc + 4.U)

    val target = Seq(
      BitPat("b?????????????????????????1101111") -> (pc + immediate), // jal
      BitPat("b?????????????????????????1100111") -> (rs1.data + immediate), //jalr
      BitPat("b?????????????????????????1100011") -> branchNextAddress, // branches
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
  //--------------------------------------------------------------------------------------------------------------------//
}

//commented out because new additions can cause issues with building other codes
object DecodeUnit extends App{
  emitVerilog(new decode())
}
