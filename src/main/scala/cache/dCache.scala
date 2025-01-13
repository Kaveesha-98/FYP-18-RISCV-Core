package pipeline.memAccess.cache

/**
  * This is a place holder code will change in the future in major ways
  * like removing a dedicated register to keep track of state.
  */

/**
  * Current implementations:
  * Handling read requests - Once read is on CurrentReq(0). The necessary 
  * cacheline is looked up on the cache and results are returned on to 
  * cacheLookUp. 
  * When cachehit - The result is ready to be accepted by pipeline
  * 
  * When cachemiss - cache pipeline is stalled
  */

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.memAccess.AXI

class dCacheRegisters extends Module {

  def getCacheIndex(address: UInt) = address(dCacheLineIndexWidth+dCacheOffsetLength, dCacheDoubleWordOffsetWidth)
  def getTagsIndex(address: UInt) = address(dCacheLineIndexWidth+dCacheOffsetLength, dCacheOffsetLength)
  def getTagOfAddress(address: UInt) = address(addressSpaceSize-1, dCacheLineIndexWidth+dCacheOffsetLength)
  // strips the last 3 LSBs
  def getDoubleWordTarget(address: UInt) = address(addressSpaceSize-1,3)

  // this will be a very simple module, we only take the
  // address as the input
  val address = UInt(XLEN.W)

  // BRAM is double word accessible only
  // Currently only direct access cache, will update to set associative later
  val cache = SyncReadMem(1 << (dCacheLineWidth+dCacheDoubleWordOffsetWidth), UInt(XLEN.W))
  val tags = SyncReadMem(1 << (dCacheLineWidth), UInt(XLEN.W))
  val valids = RegInit(VecInit.fill(1 << dCacheLineIndexWidth)(false.B))

  val lookUpResults = IO(Output(new Bundle {
    val data = UInt(XLEN.W)
    val tag = UInt(dCacheTagWidth.W)
    val valid = Bool()
  }))

  val cacheWrite = IO(Input(Valid(new Bundle {
    val address = UInt(XLEN.W) // byte-address of the first byte of the double word
    val data = UInt(XLEN.W)
    val last = Bool() // Indicates the last double of the cache set
  })))

  when (cacheWrite.valid) {
    cache.write(getCacheIndex(cacheWrite.bits.address), cacheWrite.bits.data)
    when (cacheWrite.bits.last) {
      tags.write(getTagsIndex(cacheWrite.bits.address), getTagOfAddress(cacheWrite.bits.address))
      valids(getTagsIndex(cacheWrite.bits.address)) := true.B
    }.otherwise {
      // remove access to exisiting line
      valids(getTagsIndex(cacheWrite.bits.address)) := false.B
    }
  }

  val doForward = RegNext(getDoubleWordTarget(address) === getDoubleWordTarget(cacheWrite.bits.address), false.B)
  lookUpResults.data := Mux(doForward, RegNext(cacheWrite.bits.data), cache.read(getCacheIndex(address)))
  lookUpResults.tag := Mux(doForward, RegNext(getTagOfAddress(cacheWrite.bits.address)), tags.read(getTagsIndex(address)))
  lookUpResults.valid := doForward || RegNext(valids(getTagsIndex(address)), false.B)
}

class dCache extends Module {
  val resultsFromExec = IO(ComposableIO(new resultToMemAccess))
}