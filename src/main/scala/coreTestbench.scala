import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import pipeline.memAccess.AXI

class testbench extends Module {
  // once reset programLoader will send data from the lowest byte address
  // to the highest defined byte address
  val programLoader = IO(Input(new Bundle {
    val valid = Bool()
    val byte  = UInt(8.W)
  }))

  // transactions with memory get passed only when this is high
  val programRunning = IO(Input(Bool()))

  val mem = SyncReadMem ((1 << 16) , UInt (8.W))

  val waiting :: getReadReq :: reading :: Nil = Enum(3)
  val serviceState = RegInit(waiting)
  
  val inst :: data :: Nil = Enum(2)
  val servicing = RegInit(inst)

  val dut = Module(new core)

  val ports = VecInit(dut.iPort, dut.dPort)
  // round robin style of servicing requests
  when((serviceState === waiting && !ports(servicing).ARVALID) || (ports(servicing).RVALID && ports(servicing).RREADY && ports(servicing).RLAST)) { servicing := ~servicing }

  val readRequest = Reg(new Bundle {
    // can assume all requests require 4 bytes per burst
    val address = UInt(32.W)
    val arlen   = UInt(8.W)
  })

  switch(serviceState){
    is(waiting) {
      when(ports(servicing).ARVALID) {
        readRequest.address := ports(servicing).ARADDR
        readRequest.arlen   := ports(servicing).ARLEN
      }
    }
    is(reading) {
      when(ports(servicing).RVALID && ports(servicing).RREADY) {
        readRequest.address := readRequest.address + 4.U // reads 4 bytes at a time
        readRequest.arlen   := readRequest.arlen - 1.U
      }
    }
  }

  switch(serviceState) {
    is(waiting) {
      when(ports(servicing).ARVALID && programRunning) { serviceState := getReadReq }
    }
    is(getReadReq) {
      serviceState := reading
    }
    is(reading) {
      when(ports(servicing).RVALID && ports(servicing).RREADY && ports(servicing).RLAST) { serviceState := waiting }
    }
  }

  val readData = Cat(Seq.tabulate(4)(i => 
    mem.read(readRequest.address + i.U + Mux(ports(servicing).RVALID && ports(servicing).RREADY, 4.U, 0.U))
    //Mux(ports(servicing).RVALID && ports(servicing).RREADY && (serviceState === reading), mem.read(readRequest.address + 4.U + i.U), mem.read(readRequest.address + i.U))
  ).reverse)

  Seq(inst, data).foreach( interface => {
    ports(interface).ARREADY := ((serviceState === waiting) && (servicing === interface) && programRunning)
    ports(interface).RVALID := ((serviceState === reading) && (servicing === interface))
    ports(interface).RDATA := readData
    ports(interface).RID := 0.U
    ports(interface).RLAST := (readRequest.arlen === 0.U)
    ports(interface).RRESP := 0.U
    // write interfaces driving to ground for now
    ports(interface).AWREADY := false.B
    ports(interface).WREADY := false.B
    ports(interface).BID := 0.U
    ports(interface).BVALID := false.B
    ports(interface).BRESP := 0.U
  })

  dut.peripheral.ARREADY := false.B
  dut.peripheral.AWREADY := false.B
  dut.peripheral.BVALID := false.B
  dut.peripheral.BID := 0.U
  dut.peripheral.BRESP := 0.U
  dut.peripheral.RID := 0.U
  dut.peripheral.RRESP := 0.U
  dut.peripheral.RVALID := false.B
  dut.peripheral.WREADY := false.B
  dut.peripheral.RLAST := false.B
  dut.peripheral.RDATA := 0.U

  val programAddr = RegInit(0.U(32.W))
  when(programLoader.valid) {
    mem.write(programAddr, programLoader.byte)
    programAddr := programAddr + 1.U
  }
}

object testbench extends App {
  emitVerilog(new testbench)
}
