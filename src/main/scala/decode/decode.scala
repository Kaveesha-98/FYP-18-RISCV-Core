package pipeline.decode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import pipeline.decode.constants._

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._

// once the rules is finalized, this port will be added to ports.scala
class pushInsToPipeline extends composableInterface {
  // IMPORTANT - value of x0 should never be issued with *.fromRob asserted
  val src1 = Output(new Bundle {// {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1}
    val fromRob = Bool()
    val data = UInt(64.W)
    val robAddr = UInt(robAddrWidth.W)
  })
  val src2 = Output(src1.cloneType) // {jalr, jal - 4.U}, {loads, stores, iops*, auipc - immediate}, {rops* - rs2}
  val writeData = Output(src1.cloneType)
  val instruction = Output(UInt(32.W))
  val robAddr = Input(UInt(robAddrWidth)) // allocated address in rob
}

// once the rules is finalized, this port will be added to ports.scala
class pullCommitFrmRob extends composableInterface {
  // for now consider that instructions that don't get writeback to register file are not returned back to Decode
  val robAddr = Input(UInt(robAddrWidth.W))
  val WriteBackResult = Input(UInt(64.W))
}

/**
  * This module will output whether the input instruction is a branch or not
  *
  * Input:  instr     = Instruction to check
  * Output: is_branch = Branch or not
  */
class branch_detector extends Module {
  val io = IO(new Bundle {
    val instr     = Input(UInt(32.W))
    val is_branch = Output(Bool())
  })
  val op1       = io.instr(6,5)
  val op2       = io.instr(4,2)
  val flag1     = op1 === "b11".U
  val flag2     = op2 === "b001".U || op2 === "b011".U || op2 === "b000".U
  io.is_branch :=  flag1 && flag2
}

class WriteBackResult extends Bundle {
  val toRegisterFile = UInt(1.W)
  val rd             = UInt(5.W)
  val rdData         = UInt(64.W)
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

  // receives instructions from fetch
  // and communicates the pc of the expected instruction
  val fromFetch = IO(new recivInstrFrmFetch)

  // sends results of branched to fetch unit
  val branchRes = IO(new branchResFrmDecode)

  // sends the decoded instruction to the next stage of the pipeline
  val toExec = IO(new pushInsToPipeline)

  // receives results to write into the register file
  val writeBackResult = IO(new pullCommitFrmRob)

  /**
    * Internal of the module goes here
    */

  // Assigning some wires for inputs
  val validIn = fromFetch.fired
  val writeEn = writeBackResult.toRegisterFile
  val writeData = writeBackResult.rdData
  val writeRd = Wire(UInt(5.W))
  writeRd := writeBackResult.rd
  val readyIn = toExec.ready

  // Initializing a buffer for storing the input values (pc, instruction)
  val fetchIssueBuffer = RegInit({
    val initialState = Wire(new Bundle() {
      val pc = UInt(64.W)
      val instruction = UInt(32.W)
    })
    initialState.pc := "h80000000".U - 4.U // Initial value is set for the use of expectedPC
    initialState.instruction := 0.U
    initialState
  })

  // Initializing a buffer for storing the output values to execution unit
  val decodeIssueBuffer = RegInit({
    val initialState = Wire(new Bundle() {
      val instruction = UInt(32.W)
      val pc = UInt(64.W)
      val rs1 = UInt(64.W)
      val rs2 = UInt(64.W)
      val immediate = UInt(64.W)
    })
    initialState.instruction := 0.U
    initialState.pc := 0.U
    initialState.rs1 := 0.U
    initialState.rs2 := 0.U
    initialState.immediate := 0.U
    initialState
  })

  // Initializing some intermediate wires
  val validOutFetchBuf = WireDefault(false.B) // Valid signal of fetch buffer
  val readyOutFetchBuf = WireDefault(false.B) // Ready signal of fetch buffer

  val validOutDecodeBuf = WireDefault(false.B) // Valid signal of decode buffer
  val readyOutDecodeBuf = WireDefault(false.B) // Ready signal of decode buffer

  val insType = WireDefault(0.U(3.W)) // RISC-V type of the instruction
  val immediate = WireDefault(0.U(64.W)) // Immediate value of the instruction
  val rs1Data = WireDefault(0.U(64.W)) // rs1 source register data
  val rs2Data = WireDefault(0.U(64.W)) // rs2 source register data

  val rdValid = WireDefault(true.B) // Destination register valid signal
  val rs1Valid = WireDefault(true.B) // rs1 register valid signal
  val rs2Valid = WireDefault(true.B) // rs2 register valid signal
  val stalled = WireDefault(false.B) // Stall signal

  val ins = WireDefault(0.U(32.W)) // Current instruction
  //  val pc  = WireDefault(0.U(64.W))           // Address of current instruction

  val isBranch = WireDefault(false.B) // Current instruction is a branch or not
  val expectedPC = WireDefault(0.U(64.W)) // Expected pc value from the fetch unit

  val branchValid = WireDefault(false.B) // Branch result port signals are valid or not
  val branchIsTaken = WireDefault(false.B) // Branch is taken or not
  val branchPC = WireDefault(0.U(64.W)) // Address of the branch instruction
  val branchTarget = WireDefault(0.U(64.W)) // Next address of the instruction after the branch

  // Initializing states for the FSM
  val emptyState :: fullState :: Nil = Enum(2) // States of FSM
  val stateReg = RegInit(emptyState)

  // Storing instruction and pc in a buffer
  when(validIn && readyOutFetchBuf && fromFetch.expected.pc === fromFetch.pc) {
    fetchIssueBuffer.instruction := fromFetch.instruction
    fetchIssueBuffer.pc := fromFetch.pc
  }

  ins := fetchIssueBuffer.instruction
  val pc = fetchIssueBuffer.pc

  // Storing values to the decode buffer
  when(validOutFetchBuf && readyOutDecodeBuf) {
    decodeIssueBuffer.instruction := ins
    decodeIssueBuffer.pc := pc
    decodeIssueBuffer.immediate := immediate
    decodeIssueBuffer.rs1 := rs1Data
    decodeIssueBuffer.rs2 := rs2Data
  }

  // Assigning outputs
  toExec.valid := validOutDecodeBuf
  toExec.bits.pc := decodeIssueBuffer.pc
  toExec.bits.instruction := decodeIssueBuffer.instruction
  toExec.bits.immediate := decodeIssueBuffer.immediate
  toExec.bits.rs1 := decodeIssueBuffer.rs1
  toExec.bits.rs2 := decodeIssueBuffer.rs2

  fromFetch.ready := readyOutFetchBuf
  fromFetch.expected.pc := expectedPC
  fromFetch.expected.valid := readyOutFetchBuf

  branchRes.ready := true.B
  branchRes.isBranch := isBranch
  branchRes.branchTaken := branchIsTaken
  branchRes.pc := branchPC
  branchRes.pcAfterBrnach := branchTarget

  // Deciding the instruction type
  switch(ins(6, 0)) {
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

  // Calculating the immediate value
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
      immediate := Fill(64, 0.U)
    }
    is(rtype.U) {
      immediate := Fill(64, 0.U)
    }
  }

  // Valid bits for each register
  val validBit = RegInit(VecInit(Seq.fill(32)(1.U(1.W))))
  validBit(0) := 1.U

  // Initializing the Register file
  val registerFile = Reg(Vec(32, UInt(64.W)))
  registerFile(0) := 0.U

  rs1Data := registerFile(ins(19, 15))
  rs2Data := registerFile(ins(24, 20))

  // Register writing
  when(writeEn === 1.U && validBit(writeRd) === 0.U && writeRd =/= 0.U) {
    registerFile(writeRd) := writeData
    validBit(writeRd) := 1.U
  }

  // Checking rs1 validity
  when(insType === rtype.U || insType === itype.U || insType === stype.U || insType === btype.U) {
    when(validBit(ins(19, 15)) === 0.U) {
      rs1Valid := false.B
    }
  }
  // Checking rs2 validity
  when(insType === rtype.U || insType === stype.U || insType === btype.U) {
    when(validBit(ins(24, 20)) === 0.U) {
      rs2Valid := false.B
    }
  }
  // Checking rd validity and changing the valid bit for rd
  when(insType === rtype.U || insType === utype.U || insType === itype.U || insType === jtype.U) {
    when(validBit(ins(11, 7)) === 0.U) {
      rdValid := false.B
    }
      .otherwise {
        when(validOutFetchBuf && readyIn && ins(11, 7) =/= 0.U) {
          validBit(ins(11, 7)) := 0.U
        }
      }
  }

  when(stateReg === fullState) {
    stalled := !(rdValid && rs1Valid && rs2Valid) // stall signal for FSM
  }

  // FSM for ready valid interface of fetch buffer
  // ------------------------------------------------------------------------------------------------------------------ //
  switch(stateReg) {
    is(emptyState) {
      validOutFetchBuf := false.B
      readyOutFetchBuf := true.B
      when(validIn && fromFetch.pc === fromFetch.expected.pc) {
        stateReg := fullState
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
            stateReg := emptyState
          }
        } otherwise {
          readyOutFetchBuf := false.B
        }
      }
    }
  }
  // ------------------------------------------------------------------------------------------------------------------ //

  // Initializing states for the FSM of decode buffer
  val emptyState2 :: fullState2 :: Nil = Enum(2) // States of FSM
  val stateReg2 = RegInit(emptyState2)

  // FSM for ready valid interface of decode buffer
  // ------------------------------------------------------------------------------------------------------------------ //
  switch(stateReg2) {
    is(emptyState2) {
      validOutDecodeBuf := false.B
      readyOutDecodeBuf := true.B
      when(validOutFetchBuf) {
        stateReg2 := fullState2
      }
    }
    is(fullState2) {
      validOutDecodeBuf := true.B
      when(readyIn) {
        readyOutDecodeBuf := true.B
        when(!validOutFetchBuf) {
          stateReg2 := emptyState2
        }
      } otherwise {
        readyOutDecodeBuf := false.B
      }
    }
  }
  // ------------------------------------------------------------------------------------------------------------------ //

  // Branch handling
  //  ------------------------------------------------------------------------------------------------------------------------------------------------------
  val branch_detector = Module(new branch_detector)
  branch_detector.io.instr := ins
  isBranch := branch_detector.io.is_branch

  def getResult(pattern: (chisel3.util.BitPat, chisel3.UInt), prev: UInt) = pattern match {
    case (bitpat, result) => Mux(ins === bitpat, result, prev)
  }

  when(isBranch && !stalled) {
    val branchTaken = (Seq(
      rs1Data === rs2Data,
      rs1Data =/= rs2Data,
      rs1Data.asSInt < rs2Data.asSInt,
      rs1Data.asSInt >= rs2Data.asSInt,
      rs1Data < rs2Data,
      rs1Data >= rs2Data
    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === ins(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _))

    val brachNextAddress = Mux(branchTaken, (pc + immediate), (pc + 4.U))

    val target = Seq(
      BitPat("b?????????????????????????1101111") -> (pc + immediate), // jal
      BitPat("b?????????????????????????1100111") -> (rs1Data + immediate), //jalr
      BitPat("b?????????????????????????1100011") -> brachNextAddress, // branches
    ).foldRight((pc + 4.U))(getResult)

    expectedPC := target
    when(stateReg === fullState) {
      branchValid := true.B
    }
    branchIsTaken := branchTaken
    branchPC := pc
    branchTarget := target
  } otherwise {
    expectedPC := pc + 4.U
  }
  //  -----------------------------------------------------------------------------------------------------------------------------------------------------------

}
/* 
commented out because new additions can cause issues with building other codes
object DecodeUnit extends App{
  emitVerilog(new decode())
} */