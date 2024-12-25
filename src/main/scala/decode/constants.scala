package pipeline.decode

import pipeline.configuration.coreConfiguration

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

object constants {
  val lui = "b0110111"
  val auipc = "b0010111"
  val jump = "b1101111"
  val jumpr = "b1100111"
  val cjump = "b1100011"
  val load = "b0000011"
  val store = "b0100011"
  val iops = "b0010011"
  val rops = "b0110011"
  val system = "b1110011"
  val fence = "b0001111"
  val amos = "b0101111"
  val iops32 = "b0011011"
  val rops32 = "b0111011"

  val rtype = "b000"
  val itype = "b001"
  val stype = "b010"
  val btype = "b011"
  val utype = "b100"
  val jtype = "b101"
  val ntype = "b110"

  val dataWidth = 64
  val insAddrWidth = 32
  val regCount = 32
  val csrRegCount = 4096
  val rs1Width = 5
  val rs2Width = 5
  val rdWidth = 5
  val opcodeWidth = 7

  val initialPC = coreConfiguration.instructionBase - 4      // h80000000 - 4

  val MMODE = "h0000000a00001800"
  val HMODE = "b01"
  val SMODE = "b10"
  val UMODE = "h0000000a00000000"

  val MEPC = "h341"
  val MCAUSE = "h342"
  val MSTATUS = "h300"
  val MTVEC = "h305"


  // ras actions
  val pop = 0
  val push = 1
  val popThenPush = 2

  object opcode5MSBs {
    // 2 LSBs of all 32-bit instructions are the same
    val lui = "b01101"
    val auipc = "b00101"
    val jal = "b11011"
    val jalr = "b11001"
    val condJump = "b11000"
    val load = "b00000"
    val store = "b01000"
    val iops = "b00100"
    val rops = "b01100"
    val system = "b11100"
    val fence = "b00011"
    val amos = "b01011"
    val iops32 = "b00110"
    val rops32 = "b01110"
  }

}
