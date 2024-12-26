package pipeline.configuration

/**
  * * * * * IMPORTANT * * * * * 
  * There is only one maintainer of this file. Only the maintainer is only allowed
  * to make changes to this file in the *main* branch.
  * 
  * Maintainer: Kaveesha Yalegama
  */

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

abstract class instructionEncoding
case class TypeR() extends instructionEncoding
case class TypeI() extends instructionEncoding
case class TypeS() extends instructionEncoding
case class TypeB() extends instructionEncoding
case class TypeU() extends instructionEncoding
case class TypeJ() extends instructionEncoding

object coreConfiguration {
  val robAddrWidth = 3
  val fwdAddrWidth = robAddrWidth
  val ramBaseAddress = 0x0000000010000000L
  val ramHighAddress = 0x000000001fffffffL
  val iCacheOffsetWidth = 2
  val iCacheLineWidth = 6
  val iCacheTagWidth = 32 - iCacheLineWidth - iCacheOffsetWidth - 2
  val iCacheBlockSize = (1 << iCacheOffsetWidth) // number of instructions
  val dCacheDoubleWordOffsetWidth = 3
  val dCacheLineWidth = 6
  val dCacheTagWidth = 32 - dCacheLineWidth - dCacheDoubleWordOffsetWidth - 3
  val dCacheBlockSize = (1 << dCacheDoubleWordOffsetWidth)
  val instructionBase = 0x0000000040000000L
  val instructionStart = instructionBase
  val XLEN = 64
  val ILEN = 32
  val uimmSize = 5

  val supportedExtensions = Seq('A', 'I', 'M', 'U')
  val supportsU = supportedExtensions.contains('U')

  def getImmediate[E <: instructionEncoding](instruction: UInt, encoding : E) = 
    encoding match {
      case e: TypeI => Cat(Fill(XLEN-12, instruction(31)), instruction(31, 20))
      case e: TypeS => Cat(Fill(XLEN-12, instruction(31)), instruction(31, 25), instruction(11, 7))
      case e: TypeB => Cat(Fill(XLEN-12, instruction(31)), instruction(31), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W))
      case e: TypeU => Cat(Fill(XLEN-32, instruction(31)), instruction(31, 12), 0.U(12.W))
      case e: TypeJ => Cat(Fill(XLEN-32, instruction(31)), instruction(31, 12), 0.U(12.W))
      case e: TypeR => 0.U(XLEN.W) // should not happen
    }
  val maxExceptionMcause = 19


  def isTypeI(instruction: UInt) = 
    ((instruction(6,2) === BitPat("b00??0")) && instruction(4,3) =/= "b01".U(2.W)) || /* LOAD, OP-IMM, OP-IMM-32 */
    (instruction(6,2) === "b11001".U(5.W)) /* JALR */
  def isTypeR(instruction: UInt) = 
    (instruction(6,2) === BitPat("b011?0")) /* OP, OP-32 */ || (instruction(6,2) === ("b01011".U(5.W))) /* AMO */
  def isTypeS(instruction: UInt) = 
    (instruction(6,2) === "b01000".U(5.W)) /* STORE */
  def isTypeB(instruction: UInt) = 
    (instruction(6,2) === "b11000".U(5.W)) /* BRANCH */
  def isTypeU(instruction: UInt) = 
    (instruction(6,2) === BitPat("b0?101"))
  def isTypeJ(instruction: UInt) = 
    (instruction(6,2) === "b11011".U(5.W))
  def isSystem(instruction: UInt) = 
    (instruction(6,2) === "b11100".U)

  def getImmediateUimm(instruction: UInt) =
    Cat(0.U(59.W), instruction(19,15))

  def getImmediateTypeI(instruction: UInt) = Cat(Fill(XLEN-12, instruction(31)), instruction(31, 20))
  def getImmediateTypeS(instruction: UInt) = Cat(Fill(XLEN-12, instruction(31)), instruction(31, 25), instruction(11, 7))
  def getImmediateTypeB(instruction: UInt) = Cat(Fill(XLEN-12, instruction(31)), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W))
  def getImmediateTypeU(instruction: UInt) = Cat(Fill(XLEN-32, instruction(31)), instruction(31, 12), 0.U(12.W))
  def getImmediateTypeJ(instruction: UInt) = Cat(Fill(XLEN-20, instruction(31)), instruction(19, 12), instruction(20), instruction(30, 21), 0.U(1.W))

  def isBranch(instruction: UInt) = instruction(6, 4) === "b110".U(3.W)
  def isMExtenMul(instruction: UInt) = (instruction(6, 2) === BitPat("b011?0")) && instruction(25).asBool

  
  val mstatusInitial = 0 // TODO: Set proper initial value of mstatus here
}

object mcauseEncodings {
  val InstructionAddressMisaligned = 0
  val InstructionAccessFault = 1
  val IllegalInstruction = 2
  val Breakpoint = 3
  val LoadAddressMisaligned = 4
  val LoadAccessFault = 5
  val StoreAMOAddressMisaligned = 6
  val StoreAMOAccessFault = 7
  val EnvironmentCallFromUMode = 8
  val EnvironmentCallFromSMode = 9
  // 10-Reserved 
  val EnvironmentCallFromMMode = 11
  val InstructionPageFault = 12
  val LoadPageFault = 13
  // 14-Reserved
  val StoreAMOPageFault = 15
  val DoubleTrap = 16
  // 17-Reserved
  val SoftwareCheck = 18
  val HardwareError = 19

  // for ebreak and ecall
  def mcauseForSystemCall(instruction: UInt, currentPriviledge: UInt) =
    Mux(instruction(20).asBool, mcauseEncodings.Breakpoint.U, mcauseEncodings.EnvironmentCallFromUMode.U + currentPriviledge )
}

object priviledgeEncodings {
  val machine = 3
  val supervisor = 1
  val user = 0

  val bitSize = 2
}

object CSRAddresses {
  val mstatus = 0x300
  val misa = 0x301
  val mtvec = 0x305
}
