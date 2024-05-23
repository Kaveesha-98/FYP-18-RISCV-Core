package pipeline.decode

import pipeline.configuration.coreConfiguration

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
  // For floating point
  val fload  = "b0000111"
  val fstore = "b0100111"
  val fmadd  = "b1000011"
  val fmsub  = "b1000111"
  val fnmsub = "b1001011"
  val fnmadd = "b1001111"
  val fcomp  = "b1010011"

  val rtype = "b000"
  val itype = "b001"
  val stype = "b010"
  val btype = "b011"
  val utype = "b100"
  val jtype = "b101"
  val ntype = "b110"
  // For floating point
  val ftype = "b111"

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

}
