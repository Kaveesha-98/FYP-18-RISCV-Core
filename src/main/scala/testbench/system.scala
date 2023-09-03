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
  val cpu = Module(new core)

  val memory = Module(new mainMemory)

  cpu.iPort <> memory.clients(0)
  cpu.dPort <> memory.clients(1)

  val programmer = IO(Input(memory.programmer.cloneType))
  memory.programmer := programmer

  val finishedProgramming = IO(Input(memory.finishedProgramming.cloneType))
  memory.finishedProgramming := finishedProgramming

  val peripheralUart = Module(new uart)

  val putChar = IO(peripheralUart.putChar.cloneType)
  putChar <> peripheralUart.putChar

  cpu.peripheral <> peripheralUart.client 

  val prober = IO(memory.externalProbe.cloneType)
  prober <> memory.externalProbe
}

object system extends App {
  emitVerilog(new system)
}