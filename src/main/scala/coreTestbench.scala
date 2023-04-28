import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO
import pipeline.memAccess.AXI
import pipeline.configuration.coreConfiguration

class testbench extends Module {
  // once reset programLoader will send data from the lowest byte address
  // to the highest defined byte address
  val programLoader = IO(Input(new Bundle {
    val valid = Bool()
    val byte  = UInt(8.W)
  }))

  // transactions with memory get passed only when this is high
  val programRunning = IO(Input(Bool()))

  val mem = SyncReadMem ((1 << 25) , UInt (8.W))

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
        readRequest.address := ports(servicing).ARADDR - coreConfiguration.ramBaseAddress.U
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

  val wready, bvalid = RegInit(false.B)
  val awready = RegInit(true.B)
  val waddr = RegInit(2.U(32.W))

  val programResult = IO(Output(new Bundle {
    val valid = Bool()
    val result = UInt(32.W)
  }))
  programResult.valid := false.B
  programResult.result := ports(data).WDATA
  val waddrOut = IO(Output(UInt(32.W)))
  waddrOut := waddr
  when(programRunning) {
    when(ports(data).AWVALID && ports(data).AWREADY) { waddr := (Cat(ports(data).AWADDR(31,2), 0.U(2.W)) - coreConfiguration.ramBaseAddress.U) }
    .elsewhen(ports(data).WREADY && ports(data).WVALID) { waddr := waddr + 4.U }

    when(awready) { awready := !ports(data).AWVALID }
    .otherwise { awready := (ports(data).BVALID && ports(data).BREADY) }

    when(wready) { wready := !(ports(data).WVALID && ports(data).WLAST) }
    .otherwise { wready := ports(data).AWVALID && ports(data).AWREADY }

    when(bvalid) { bvalid := !ports(data).BREADY }
    .otherwise { bvalid := ports(data).WLAST && ports(data).WVALID && ports(data).WREADY }

    when(ports(data).WVALID && ports(data).WREADY) {
      programResult.valid := waddr === (0x80001000L).U
      (0 until 4).foreach( i => {
        when(ports(data).WSTRB(i).asBool) { mem.write(waddr + i.U, ports(data).WDATA(7 + 8*i, 8*i)) }
      })
    }
  }

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
  ports(data).AWREADY := awready
  ports(data).WREADY := wready
  ports(data).BID := 0.U
  ports(data).BVALID := bvalid
  ports(data).BRESP := 0.U

  val testResult = RegInit((new Bundle {
    val valid = Bool()
    val result = UInt(8.W)
  }).Lit(
    _.result -> 0.U,
    _.valid -> false.B
  ))

  val awreadyP = RegInit(true.B)
  dut.peripheral.AWREADY := awreadyP

  val wreadyP, bvalidP = RegInit(false.B)
  dut.peripheral.WREADY := wreadyP
  dut.peripheral.BVALID := bvalidP

  when(dut.peripheral.AWVALID && dut.peripheral.AWREADY) { awreadyP := false.B }
  .elsewhen(dut.peripheral.BVALID && dut.peripheral.BREADY) { awreadyP := true.B }

  when(dut.peripheral.AWVALID && dut.peripheral.AWREADY) { wreadyP := true.B }
  .elsewhen(dut.peripheral.WREADY && dut.peripheral.WVALID && dut.peripheral.WLAST) { wreadyP := false.B }

  when(dut.peripheral.WREADY && dut.peripheral.WVALID && dut.peripheral.WLAST) { bvalidP := true.B }
  .elsewhen(dut.peripheral.BVALID && dut.peripheral.BREADY) { bvalidP := false.B}

  when(dut.peripheral.WREADY && dut.peripheral.WVALID && dut.peripheral.WLAST) {
    testResult.result := dut.peripheral.WDATA(31, 24)
    testResult.valid := true.B
  }

  val results = IO(Output(testResult.cloneType))
  results := testResult

  val arreadyP = RegInit(true.B)
  val rvalidP = RegInit(false.B)

  when(dut.peripheral.ARREADY && dut.peripheral.ARVALID) { arreadyP := false.B }
  .elsewhen(dut.peripheral.RVALID && dut.peripheral.RREADY) { arreadyP := true.B }

  when(dut.peripheral.ARREADY && dut.peripheral.ARVALID) { rvalidP := true.B }
  .elsewhen(dut.peripheral.RVALID && dut.peripheral.RREADY) { rvalidP := false.B }

  dut.peripheral.ARREADY := arreadyP
  dut.peripheral.BID := 0.U
  dut.peripheral.BRESP := 0.U
  dut.peripheral.RID := 0.U
  dut.peripheral.RRESP := 0.U
  dut.peripheral.RVALID := rvalidP
  dut.peripheral.RLAST := true.B
  dut.peripheral.RDATA := 8.U

  val programAddr = RegInit(0.U(32.W))
  when(programLoader.valid) {
    mem.write(programAddr, programLoader.byte)
    programAddr := programAddr + 1.U
  }
  val waddrP = Reg(UInt(32.W))
  when(dut.peripheral.AWVALID && dut.peripheral.AWREADY) { waddrP := dut.peripheral.AWADDR }

  val uart = IO(Output(new Bundle {
    val valid = Bool()
    val character = UInt(8.W)
  }))
  uart.valid := (waddrP === (0xE0000030L).U) && dut.peripheral.AWVALID && dut.peripheral.AWREADY
  uart.character := dut.peripheral.WDATA(7,0)
}

object testbench extends App {
  emitVerilog(new testbench)
}
