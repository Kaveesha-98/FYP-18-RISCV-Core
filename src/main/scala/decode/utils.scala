package pipeline.decode

import chisel3._
import chisel3.util.{Cat, Fill, is, switch}
import pipeline.decode.constants._
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

object utils {
  /** once the rules is finalized, this port will be added to ports.scala */
  class pushInsToPipeline extends composableInterface {
    val src1 = Output(new Src)
    /** IMPORTANT - value of x0 should never be issued with *.fromRob asserted */
    val src2 = Output(src1.cloneType)
    /** {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1} */
    val writeData = Output(src1.cloneType)
    /** {jalr, jal - 4.U}, {loads, stores, iops*, auipc - immediate}, {rops* - rs2} */
    val instruction = Output(UInt(insAddrWidth.W))
    val pc = Output(UInt(dataWidth.W))
    val robAddr = Input(UInt(robAddrWidth.W))

    /** allocated address in rob */
  }

  /** once the rules is finalized, this port will be added to ports.scala */
  class pullCommitFrmRob extends composableInterface {
    /** for now consider that instructions that don't get writeback to register file are not returned back to Decode */
    val robAddr = Input(UInt(robAddrWidth.W))
    val rdAddr = Input(UInt(rdWidth.W))
    val writeBackData = Input(UInt(dataWidth.W))
  }

  class Src extends Bundle {
    val fromRob = Bool()
    /** ROB address of the source */
    val data = UInt(dataWidth.W)
    /** Data of the source */
    val robAddr = UInt(robAddrWidth.W)

    /** Where the source data can be found : (true -> ROB, false -> register file) */


  }

  class Validity extends Bundle {
    /** Valid signals for Data in the register and ROB Address */
    val rs1Data = Bool()
    val rs1RobAddr = Bool()
    val rs2Data = Bool()
    val rs2RobAddr = Bool()
    val writeData = Bool()
    val writeRobAddr = Bool()
  }

  class Branch extends Bundle {
    /** Details for branching */
    val isBranch = Output(Bool())
    val branchTaken = Output(Bool())
    val pc = Output(UInt(32.W))
    val pcAfterBrnach = Output(UInt(32.W))
  }

  def getInsType(opcode: UInt): UInt = {
    val insType = WireDefault(0.U(3.W))
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
    insType
  }

  def getImmediate(instruction: UInt, instrucitonType: UInt): UInt = {
    val immediate = WireDefault(0.U(dataWidth.W))
    switch(instrucitonType) {
      is(itype.U) {
        immediate := Cat(Fill(53, instruction(31)), instruction(30, 20))
      }
      is(stype.U) {
        immediate := Cat(Fill(53, instruction(31)), instruction(30, 25), instruction(11, 7))
      }
      is(btype.U) {
        immediate := Cat(Fill(53, instruction(31)), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W))
      }
      is(utype.U) {
        immediate := Cat(Fill(32, instruction(31)), instruction(31, 12), 0.U(12.W))
      }
      is(jtype.U) {
        immediate := Cat(Fill(44, instruction(31)), instruction(19, 12), instruction(20), instruction(30, 25), instruction(24, 21), 0.U(1.W))
      }
      is(ntype.U) {
        immediate := Fill(dataWidth, 0.U)
      }
      is(rtype.U) {
        immediate := Fill(dataWidth, 0.U)
      }
    }
    immediate
  }

}