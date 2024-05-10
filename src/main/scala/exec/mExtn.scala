package pipeline.exec

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._

abstract class mExtn extends Module {
  val inputs = IO(Flipped(DecoupledIO(new Bundle {
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val mOp = UInt(4.W) // {is32bitOp, funct3}
  })))

  val output = IO(DecoupledIO(UInt(64.W)))

  // checks for 14th bit returns whether it can be serviced by the module
  def turnOn(x: UInt): Bool
}

class multiplier extends mExtn {

  def turnOn(x: UInt): Bool = !x(14).asBool

  val result = RegInit(new Bundle {
    val valid = Bool()
    val data = UInt(64.W)
  } Lit(_.valid -> false.B))

  output.valid := result.valid
  output.bits := result.data

  val mOp = RegEnable(inputs.bits.mOp, inputs.fire)

  val muls = Reg(Vec (6, UInt (96.W)))

  val partialMuls32x32 = Seq(
    inputs.bits.src1(31, 0) * inputs.bits.src2(31, 0), 
    inputs.bits.src1(63, 32) * inputs.bits.src2(31, 0),
    Cat(inputs.bits.src1(63), inputs.bits.src1(63, 32)).asSInt * Cat(0.U(1.W), inputs.bits.src2(31, 0)).asSInt,
    inputs.bits.src1(31, 0) * inputs.bits.src2(63, 32),
    Cat(0.U(1.W), inputs.bits.src1(31, 0)).asSInt * Cat(inputs.bits.src2(63), inputs.bits.src2(63, 32)).asSInt,
    inputs.bits.src1(63, 32) * inputs.bits.src2(63, 32), 
    Cat(inputs.bits.src1(63), inputs.bits.src1(63, 32)).asSInt * Cat(0.U(1.W), inputs.bits.src2(63, 32)).asSInt,
    inputs.bits.src1(63, 32).asSInt * inputs.bits.src2(63, 32).asSInt, 
    inputs.bits.src1(31, 0).asSInt * inputs.bits.src2(31, 0).asSInt
  )
  val narrowMuls = Reg(Vec(9, UInt(64.W)))
  narrowMuls zip partialMuls32x32.map(_.asUInt) foreach{ case(reg, mul) => reg := mul }

  muls(0) := (narrowMuls(0) + Cat(narrowMuls(1), 0.U(32.W)))// inputs.bits.src1 * inputs.bits.src2(31, 0)
  muls(1) := narrowMuls(3) + Cat(narrowMuls(5), 0.U(32.W)) // inputs.bits.src1 * inputs.bits.src2(63, 32)
  muls(2) := narrowMuls(3) + Cat(narrowMuls(6), 0.U(32.W))// (inputs.bits.src1.asSInt * Cat(0.U(1.W),inputs.bits.src2(63, 32)).asSInt)(95, 0).asUInt
  muls(3) := Cat(Fill(32, narrowMuls(4)(63)), narrowMuls(4)) + Cat(narrowMuls(7), 0.U(32.W)) // (inputs.bits.src1.asSInt * inputs.bits.src2(63, 32).asSInt)(95, 0).asUInt
  muls(4) := narrowMuls(0) + Cat(narrowMuls(2), 0.U(32.W))// (inputs.bits.src1.asSInt * Cat(0.U(1.W), inputs.bits.src2(31, 0)).asSInt)(95, 0).asUInt
  muls(5) := narrowMuls(8)// (inputs.bits.src1(31, 0).asSInt * inputs.bits.src2(31, 0).asSInt).asUInt
  
  when(!result.valid) {
    result.data := Mux(mOp(3).asBool, Cat(Fill(32, muls(5)(31)), muls(5)(31, 0)), 
    VecInit(
      (Cat(Fill(32, muls(4)(95)), muls(4)) + Cat(muls(3), 0.U(32.W)))(63, 0),
      (Cat(Fill(32, muls(4)(95)), muls(4)) + Cat(muls(3), 0.U(32.W)))(127, 64),
      (Cat(Fill(32, muls(4)(95)), muls(4)) + Cat(muls(2), 0.U(32.W)))(127, 64),
      (muls(0) + Cat(muls(1), 0.U(32.W)))(127, 64)
    )(mOp(1, 0)))
  }

  val step = RegInit(new Bundle {
    val narrowMuls = Bool() // narrowMul done
    val muls = Bool() // 1 st step of additions
  } Lit(_.narrowMuls -> false.B, _.muls -> false.B))
  Seq(inputs.fire, step.narrowMuls) zip Seq(step.narrowMuls, step.muls) foreach { case(next, reg) => reg := next }

  when(!result.valid) { result.valid := step.muls }
  .otherwise { result.valid := !output.fire }

  inputs.ready := !Seq(step.narrowMuls, step.muls, result.valid).reduce(_ || _)
}

class divider extends mExtn {

  def turnOn(x: UInt): Bool = x(14).asBool

  val division = RegInit(new Bundle {
    val request = new Bundle {
      val valid = Bool()
      val mOp = inputs.bits.mOp.cloneType
      val rs1 = UInt(64.W)
      val rs2 = UInt(64.W)
    }
    val quotient = UInt(65.W) // initally divident
    val remainder = UInt(65.W) // initially zero
    val divisor = UInt(65.W)
    val counter = UInt(7.W) // when zero operation finished
    val resultNegative = Bool()
  } Lit(_.request.valid -> false.B))

  when(division.counter.orR){
    division.remainder := Cat(division.remainder(63, 0), division.quotient(64)) + Mux(division.remainder(64).asBool, division.divisor, - division.divisor)
    division.quotient := Cat(division.quotient(63, 0), ~(Cat(division.remainder(63, 0), division.quotient(64)) + Mux(division.remainder(64).asBool, division.divisor, - division.divisor))(64))
  }
  
  division.counter := Mux(division.counter.orR, division.counter - 1.U, division.counter)

  when(inputs.fire && inputs.bits.mOp(2).asBool) {
    division.counter := 65.U
    division.divisor := inputs.bits.src2
    division.quotient := inputs.bits.src1
    when(inputs.bits.mOp(3).asBool) {
      division.divisor := Cat(0.U(33.W), inputs.bits.src2(31, 0))
      division.quotient := Cat(0.U(33.W), inputs.bits.src1(31, 0))
    }
    division.remainder := 0.U
    division.request.valid := true.B
    division.request.mOp := inputs.bits.mOp
    division.request.rs1 := inputs.bits.src1
    division.request.rs2 := inputs.bits.src2
    division.resultNegative := false.B
    when(!inputs.bits.mOp(0).asBool){
      division.resultNegative := inputs.bits.src1(63).asBool ^ inputs.bits.src2(63).asBool
      when(inputs.bits.src1(63).asBool) { division.quotient := Cat(0.U(1.W), (- inputs.bits.src1)(63, 0)) }
      when(inputs.bits.src2(63).asBool) { division.divisor := Cat(0.U(1.W), (- inputs.bits.src2)(63, 0)) }
      when(inputs.bits.mOp(3).asBool) {
        when(inputs.bits.src1(31).asBool) { division.quotient := Cat(0.U(33.W), (- inputs.bits.src1(31, 0))(31, 0)) }
        when(inputs.bits.src2(31).asBool) { division.divisor := Cat(0.U(33.W), (- inputs.bits.src2(31, 0))(31, 0)) }
      }
    }
  }

  val results = RegInit(new Bundle {
    val valid = Bool()
    val data = UInt(64.W)
  } Lit(_.valid -> false.B))

  when(division.request.valid && !division.counter.orR) {
    results.data := {
      val quotient32 = Mux((division.request.rs1(31).asBool ^ division.request.rs2(31).asBool) && !division.quotient.andR, (- division.quotient)(31, 0), division.quotient(31, 0))
      val remainder64Unsigned = Mux(division.remainder(64).asBool, division.remainder + division.divisor, division.remainder)
      val remainder32Signed = Mux((division.request.rs1(31).asBool), - remainder64Unsigned, remainder64Unsigned)
      
      VecInit(
      Mux((division.request.rs1(63).asBool ^ division.request.rs2(63).asBool) && !division.quotient.andR, - division.quotient, division.quotient),
      division.quotient,
      Mux((division.request.rs1(63).asBool), - remainder64Unsigned, remainder64Unsigned),
      remainder64Unsigned,
      Cat(Fill(32, quotient32(31)), quotient32(31, 0)),
      Cat(Fill(32, division.quotient(31)), division.quotient(31, 0)),
      Cat(Fill(32, remainder32Signed(31)), 
        remainder32Signed(31, 0)),
      Cat(Fill(32, remainder64Unsigned(31)), 
        remainder64Unsigned(31, 0))
    )(Cat(division.request.mOp(3), division.request.mOp(1, 0)))
  }
    results.valid := true.B
    division.request.valid := false.B
  }

  output.valid := results.valid
  output.bits := results.data

  when(output.fire) { results.valid := false.B }

  inputs.ready := !division.counter.orR && !output.valid && !division.request.valid
}
