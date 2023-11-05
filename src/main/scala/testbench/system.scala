import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.memAccess.AXI
import _root_.testbench.mainMemory
import _root_.testbench.uart

class system extends Module {
  

  val cpu = Module(new core {
    val registersOut = IO(Output(decode.registersOut.cloneType))
    registersOut := decode.registersOut


    // Debug Signals

    val branchOut = IO(Output(new Bundle() {
      val mispredicted = Bool()
      val resfired = Bool()
      val isbranch = Bool()
      val btbhit = Bool()
      val fetchsent = Bool()
      val resPC = UInt(64.W)
      val decodePC = UInt(64.W)
      val decodeIns = UInt(32.W)
      val isRas = Bool()
      val rasAction = UInt(64.W)
      val curPC = UInt(64.W)
      val predRasAction = UInt(64.W)
      val rasOveride = UInt(64.W)
      val rasLogicTrigger = UInt(64.W)
      val btbVal = UInt(64.W)
    }))
    branchOut.mispredicted := fetch.misprediction
    branchOut.resfired := fetch.resfired
    branchOut.isbranch := fetch.isabranch
    branchOut.btbhit := fetch.btbhit
    branchOut.fetchsent := fetch.fetchsent
    branchOut.resPC := fetch.resPC
    branchOut.decodePC := decode.decodePC
    branchOut.decodeIns := decode.decodeIns
    branchOut.isRas := fetch.isras
    branchOut.rasAction := fetch.rasAction
    branchOut.curPC := fetch.curPC
    branchOut.predRasAction := fetch.predRasAction
    branchOut.rasOveride := fetch.rasOveride
    branchOut.rasLogicTrigger := fetch.rasLogicTrigger
    branchOut.btbVal := fetch.btbVal

    val execOut = IO(Output(new Bundle {
      val fired = Bool()
      val instruction = UInt(32.W)
      val pc = UInt(64.W)
      val src1 = UInt(64.W)
      val src2 = UInt(64.W)
      val writeData = UInt(64.W)
    }))
    execOut.fired := exec.fromIssue.fired
    execOut.instruction := exec.fromIssue.instruction
    execOut.pc := decode.toExec.pc
    execOut.src1 := exec.fromIssue.src1
    execOut.src2 := exec.fromIssue.src2
    execOut.writeData := exec.fromIssue.writeData


    val fetchOut = IO(Output(new Bundle {
      val fired = Bool()
      val pc = UInt(64.W)
    }))
    fetchOut.fired := fetch.toDecode.fired
    fetchOut.pc := fetch.toDecode.pc

    val robOut = IO(Output(new Bundle() {
      val commitFired = Bool()
      val pc         = UInt(64.W)
      val interrupt = Bool()
    }))
    robOut.commitFired := rob.commit.fired
    robOut.pc          := rob.commit.mepc
    robOut.interrupt   := rob.commit.execptionOccured && rob.commit.mcause(63).asBool

    val sampleOut = IO(Output(decode.csrWriteOut.cloneType))
    sampleOut := decode.csrWriteOut
  })

  val memory = Module(new mainMemory)

  cpu.iPort <> memory.clients(0)
  cpu.dPort <> memory.clients(1)

  val programmer = IO(Input(memory.programmer.cloneType))
  memory.programmer := programmer

  val finishedProgramming = IO(Input(memory.finishedProgramming.cloneType))
  memory.finishedProgramming := finishedProgramming

  val peripheralUart = Module(new uart{
    val putCharOut = IO(Output(putChar.cloneType))
    putCharOut := putChar
  })

  val putChar = IO(Output(peripheralUart.putCharOut.cloneType))
  putChar := peripheralUart.putCharOut

  cpu.peripheral <> peripheralUart.client 

  val prober = IO(memory.externalProbe.cloneType)
  prober <> memory.externalProbe

  val registersOut = IO(Output(cpu.registersOut.cloneType))
  registersOut := cpu.registersOut

  val robOut = IO(Output(cpu.robOut.cloneType))
  robOut := cpu.robOut

  val sampleOut = IO(Output(cpu.sampleOut.cloneType))
  sampleOut := cpu.sampleOut

  cpu.MTIP := peripheralUart.MTIP
}

object system extends App {
  emitVerilog(new system)
}