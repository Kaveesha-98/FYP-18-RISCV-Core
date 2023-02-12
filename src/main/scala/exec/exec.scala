package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._

class exec extends Module {
  /**
    * Inputs and Outputs of the module
    */

  // recieve instruction from decode unit
  val decodeIssuePort = IO(Flipped(DecoupledIO(new DecodeIssuePort())))

  // sending the instruction down the pipeline
  val aluIssuePort = IO(DecoupledIO(new AluIssuePort()))

  def getsWriteBack(recievedIns: DecodeIssuePort): AluIssuePort = {
    // function to execute a given instruction
    val result = Wire(new AluIssuePort)
    def getResult(pattern: (chisel3.util.BitPat, chisel3.UInt), prev: UInt) = pattern match {
      case (bitpat, result) => Mux(recievedIns.instruction === bitpat, result, prev)
    }

    def result32bit(res: UInt) =
      Cat(Fill(32, res(31)), res(31, 0))

    val immediate = recievedIns.immediate
    val pc = recievedIns.pc
    val rs1 = recievedIns.rs1
    val rs2 = recievedIns.rs2

    /**
      * For aithmatic operations that includes immedaite, rs2 will have that immediate.
      */

    result.aluResult := {
      /**
        * 64 bit operations, indexed with funct3, op-imm, op
        */
      val arithmetic64 = VecInit.tabulate(8)(i => i match {
        case 0 => Mux(Cat(recievedIns.instruction(30), recievedIns.instruction(5)) === "b11".U, rs1 - rs2, rs1 + rs2)
        case 1 => (rs1 << rs2(5, 0))
        case 2 => (rs1.asSInt < rs2.asSInt).asUInt
        case 3 => (rs1 < rs2).asUInt
        case 4 => (rs1 ^ rs2)
        case 5 => Mux(recievedIns.instruction(30).asBool, (rs1.asSInt >> rs2(5, 0)).asUInt, (rs1 >> rs2(5, 0)))
        case 6 => (rs1 | rs2)
        case 7 => (rs1 & rs2)
      })(recievedIns.instruction(14, 12))

      /**
        * 32 bit operations, indexed with funct3, op-imm-32, op-32
        */
      val arithmetic32 = VecInit.tabulate(4)(i => i match {
        case 0 => Mux(Cat(recievedIns.instruction(30), recievedIns.instruction(5)) === "b11".U, result32bit(rs1 - rs2), result32bit(rs1 + rs2)) // add & sub
        case 1 => (result32bit(rs1 << rs2(4, 0))) // sll\iw
        case 2 => (result32bit(rs1 << rs2(4, 0))) // filler
        case 3 => Mux(recievedIns.instruction(30).asBool, result32bit((rs1(31, 0).asSInt >> rs2(4, 0)).asUInt), result32bit(rs1(31, 0) >> rs2(4, 0))) // sra\l\iw
      })(Cat(recievedIns.instruction(14), recievedIns.instruction(12)))

      /**
        * Taken from register mapping in the instruction listing risc-spec
        */
      VecInit.tabulate(7)(i => i match {
        case 0 => (rs1 + immediate) // address calculation for memory access
        case 1 => (pc + 4.U) // jal link address
        case 2 => (pc + 4.U) //(63, 0) // filler
        case 3 => (pc + 4.U) //(63, 0) jalr link address
        case 4 => arithmetic64 // (63, 0) op-imm, op
        case 5 => (immediate + Mux(recievedIns.instruction(5).asBool, 0.U, pc)) // (63, 0) // lui and auipc
        case 6 => arithmetic32 // op-32, op-imm-32
      })(recievedIns.instruction(4, 2))
    }

    val branchTaken = (Seq(
      rs1 === rs2,
      rs1 =/= rs2,
      rs1.asSInt < rs2.asSInt,
      rs1.asSInt >= rs2.asSInt,
      rs1 < rs2,
      rs1 >= rs2
    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === recievedIns.instruction(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _))

    val brachNextAddress = Mux(branchTaken, (pc + immediate), (pc + 4.U))

    result.nextInstPtr := Seq(
        BitPat("b?????????????????????????1101111") -> (pc + immediate), // jal
        BitPat("b?????????????????????????1100111") -> (rs1 + immediate), //jalr
        BitPat("b?????????????????????????1100011") -> brachNextAddress, // branches
    ).foldRight((pc + 4.U))(getResult)
    result.instruction := recievedIns.instruction
    result.rs2 := recievedIns.rs2
    result
  }

  val decodeIssueBuffer = RegInit(new Bundle{
    val valid   = Bool()
    val bits    = (new DecodeIssuePort())
  }.Lit(
    _.valid -> false.B,
    _.bits.pc -> 0.U,
    _.bits.instruction -> 0.U,
    _.bits.rs1 -> 0.U,
    _.bits.rs2 -> 0.U,
    _.bits.immediate -> 0.U
  ))

  val aluIssueBuffer = RegInit(new Bundle{
    val valid   = Bool()
    val bits    = (new AluIssuePort())
  }.Lit(
    _.valid -> false.B,
    _.bits.nextInstPtr -> 0.U,
    _.bits.instruction -> 0.U,
    _.bits.aluResult -> 0.U,
    _.bits.rs2 -> 0.U
  ))

  // no instructions are accepted while there is a buffered instruction
  decodeIssuePort.ready := !decodeIssueBuffer.valid

  // an instruction is buffered if the older instruction is not yet accepted by the memory access unit
  when((decodeIssuePort.ready && decodeIssuePort.valid) && (aluIssuePort.valid && !aluIssuePort.ready)) {
    decodeIssueBuffer.bits  := decodeIssuePort.bits
    decodeIssueBuffer.valid := true.B
  }.elsewhen(decodeIssueBuffer.valid && aluIssuePort.valid && aluIssuePort.ready) {
    // older instruction was accepted by memory unit, processing the buffered instruction
    decodeIssueBuffer.valid := false.B
  }
  // buffered instruction is given priority
  val processingEntry = Mux(decodeIssueBuffer.valid, decodeIssueBuffer.bits, decodeIssuePort.bits) 

  val entryValid = decodeIssueBuffer.valid || decodeIssuePort.valid

  when(entryValid && (!aluIssueBuffer.valid || aluIssuePort.ready)) {
    // either the old instruction was accepted, or no old instruction
    aluIssueBuffer.bits := getsWriteBack(processingEntry)
    aluIssueBuffer.valid := true.B
  }.elsewhen(!entryValid && (aluIssuePort.valid && aluIssuePort.ready)) {
    aluIssueBuffer.valid := false.B
  }

  aluIssuePort.valid := aluIssueBuffer.valid
  aluIssuePort.bits   := aluIssueBuffer.bits
}

object exec extends App {
  (new stage.ChiselStage).emitVerilog(new exec)
}