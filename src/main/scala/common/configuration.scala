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
import pipeline.ports.validBundle

abstract class instructionEncoding
case class TypeR() extends instructionEncoding
case class TypeI() extends instructionEncoding
case class TypeS() extends instructionEncoding
case class TypeB() extends instructionEncoding
case class TypeU() extends instructionEncoding
case class TypeJ() extends instructionEncoding

object priviledgeEncodings {
  val machine = 3
  val supervisor = 1
  val user = 0

  val bitSize = 2
}

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
  val vendorid = 0
  val impid = 0
  val archid = 0

  val supportedExtensions = Seq('A', 'I', 'M', 'U')
  val supportsU = supportedExtensions.contains('U')

  def getMISA = supportedExtensions.foldLeft(0x2L << (XLEN-2)){ case(misa, ext) => misa + (1 << (ext - 'A'))}

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

  def WPRIbits(noOfBits: Int) = 0.U(noOfBits.W)

  // val mstatusInitial = 0 // TODO: Set proper initial value of mstatus here
  val mstatusInitial = {
    val SD = 0L // (R) All FS, XS and FS are read-only zero
    val MDT = 0L // We don't implement Smrnmi extension
    val MPELP = 0L // Don't think I have implemented any landind pad instruction
    val MPV = 0L // Hypervisor not implemented, a default value was not given in spec
    val GVA = 0L // Hypervisor not implemented, a default value was not given in spec
    val MBE = 0L // little endian (fixed)
    val SBE = 0L // S not implemented
    val SXL = 0L // S not implemented
    val UXL = 2L // 64-bit fixed
    val SDT = 0L // (R) We don't implement S mode (A default value was not given in spec)
    val SPELP = 0L // (R) No landing pad instructions implemented I think
    val TSR = 0L // (R) S mode is not supported yet
    val TW = 0L // (R/W) we never had a problem in letting wfi happen in U-mode
    val TVM = 0L // We don't implement S-mode
    val MXR = 0L // S-mode is not implemented
    val SUM = 0L // S-mode is not implemented
    val MPRV = 0L // Initially loads and stores will happen without any protection
    val XS = 0L // No additional user extensions requiring new state implemented
    val FS = 0L // Floating point not implemented
    val MPP = priviledgeEncodings.user
    val VS = 0L // vectors not implemented
    val SPP = 0L // Anyway S not implemented
    val MPIE = 0L // I don't think this matters
    val UBE = 0L // little-endian (fixed)
    val SPIE = 0L // S-mode not implemented
    val MIE = 0L // No one wants to deal with interrupts at the start
    val SIE = 0L // S-mode not implemented

    (
      0L + (SD<<63) + (MDT<<42) + (MPELP<<41) + (MPV<<39) + (GVA<<38) +
      (MBE<<37) + (SBE<<36) + (SXL<<34) + (UXL<<32) + (SDT<<24) + (SPELP<<23) +
      (TSR<<22) + (TW<<21) + (TVM<<20) + (MXR<<19) + (SUM<<18) + (MPRV<<17) + 
      (XS<<15) + (FS<<13) + (MPP<<11) + (VS<<9) + (SPP<<8) + (MPIE<<7) + 
      (UBE<<6) + (SPIE<<5) + (MIE<<3) + (SIE<<1)
    )
  }

  def getMPPfromMSTATUS(mstatus: UInt) = mstatus(12,11)
  def getMPIEfromMSTATUS(mstatus: UInt) = mstatus(7)
  def getMIEfromMSTATUS(mstatus: UInt) = mstatus(3)
  def getMPRVfromMSTATUS(mstatus: UInt) = mstatus(17)
  def getTWfromMSTATUS(mstatus: UInt) = mstatus(21)

  def formatBeforeWritingToMSTATUS(value: UInt, mstatus: UInt) = {
    val SD = 0.U(1.W) // (R) All FS, XS and FS are read-only zero
    val MDT = 0.U(1.W) // We don't implement Smrnmi extension
    val MPELP = 0.U(1.W) // Don't think I have implemented any landind pad instruction
    val MPV = 0.U(1.W) // Hypervisor not implemented, a default value was not given in spec
    val GVA = 0.U(1.W) // Hypervisor not implemented, a default value was not given in spec
    val MBE = 0.U(1.W) // little endian (fixed)
    val SBE = 0.U(1.W) // S not implemented
    val SXL = 0.U(2.W) // S not implemented
    val UXL = 2.U(2.W) // 64-bit fixed
    val SDT = 0.U(1.W) // (R) We don't implement S mode (A default value was not given in spec)
    val SPELP = 0.U(1.W) // (R) No landing pad instructions implemented I think
    val TSR = 0.U(1.W) // (R) S mode is not supported yet
    val TW = getTWfromMSTATUS(value) // (R/W) we never had a problem in letting wfi happen in U-mode
    val TVM = 0.U(1.W) // We don't implement S-mode
    val MXR = 0.U(1.W) // S-mode is not implemented
    val SUM = 0.U(1.W) // S-mode is not implemented
    val MPRV = getMPRVfromMSTATUS(value) // Initially loads and stores will happen normally
    val XS = 0.U(2.W) // No additional user extensions requiring new state implemented
    val FS = 0.U(2.W) // Floating point not implemented
    val MPP = MuxLookup(getMPPfromMSTATUS(value), getMPPfromMSTATUS(mstatus), /* Use existing value if new is illegal */ 
    Seq(priviledgeEncodings.machine, priviledgeEncodings.user).map(_.U -> getMPPfromMSTATUS(value)))
    val VS = 0.U(2.W) // vectors not implemented
    val SPP = 0.U(1.W) // Anyway S not implemented
    val MPIE = getMPIEfromMSTATUS(value) // I don't think this matters
    val UBE = 0.U(1.W) // little-endian (fixed)
    val SPIE = 0.U(1.W) // S-mode not implemented
    val MIE = getMIEfromMSTATUS(value)
    val SIE = 0.U(1.W) // S-mode not implemented

    Cat(
      SD, WPRIbits(20), MDT, MPELP, WPRIbits(1), MPV, GVA, MBE, SBE, SXL, UXL,
      WPRIbits(7), SDT, SPELP, TSR, TW, TVM, MXR, SUM, MPRV, XS, FS, MPP, VS,
      SPP, MPIE.asUInt, UBE, SPIE, WPRIbits(1), MIE, WPRIbits(1), SIE, WPRIbits(1)
    )
  }

  def setMSTATUStoHandleTrap(mstatus: UInt, currentPriviledge: UInt) = {
    Cat(mstatus(63,13), currentPriviledge, mstatus(10, 8), mstatus(3).asUInt, mstatus(6,4), 0.U(1.W), mstatus(2,0))
  }

  def setMSTATUSafterTrapReturn(mstatus: UInt) =
    Cat(
      mstatus(63,18), 
      Mux(getMPPfromMSTATUS(mstatus) === priviledgeEncodings.machine.U, getMPRVfromMSTATUS(mstatus).asUInt, 0.U(1.W)), priviledgeEncodings.user.U(2.W),
      mstatus(16,13), mstatus(10,8), 0.U(1.W), mstatus(6,4), mstatus(7), mstatus(2,0)
    )

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

object CSRAddresses {
  // Unpriviledged Floating Point CSRs: None implemented for now
  val fflags = 0x001
  val frm = 0x002
  val fcsr = 0x003

  // Unpriviledged Zicfiss extension CSR: None implemented for now
  val ssp = 0x011

  // Unpriviledged Counter/Timers
  val cycle = 0xC00
  val time = 0xC01
  val instret = 0xC02
  // No other performance monitoring CSRs implemented yet

  // Not going to bother with supervisor-level CSR addresses for now

  // Machine-level CSR addresses
  // Machine information registers
  val mvendorid = 0xF11
  val marchid = 0xF12
  val mimpid = 0xF13
  val mhartid = 0xF14
  val mconfigptr = 0xF15
  // Machine Trap Setup
  val mstatus = 0x300
  val misa = 0x301
  val medeleg = 0x302
  val mideleg = 0x303
  val mie = 0x304
  val mtvec = 0x305
  val mcounteren = 0x306
  // Machine Trap Handling
  val mscratch = 0x340
  val mepc = 0x341
  val mcause = 0x342
  val mtval = 0x343
  val mip = 0x344
  val mtinst = 0x34A
  val mtval2 = 0x34B
  // Machine Configuration
  val menvcfg = 0x30A
  val mseccfg = 0x747
  // ignoring pmpcfg*, pmpaddr* and mstateen* registers for now
}
