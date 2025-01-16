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

/**
  * All incoming instructions from Exec will be served here, outer memAccess
  * module will take care of fetching data for cache-miss instances.
  * 
  * All instructions will input an address to fetch data from and then move
  * to waitOnCacheRead register. All instructions will enter through the 
  * interface resultsFromExec. If a stall is detected, then the instruction
  * will be moved to stalledResultFromExec. 
  * 
  * All instructions will occupy waitOnCacheRead register only for just one
  * cycle. Then depending on the state of resultsFromDCache register, the 
  * instruction will then occupy resultsFromDCache register or stalledResultsFromDCache
  * register.
  * 
  * Instructions that are sent to stalledResultsFromDCache will eventually
  * be sent to resultsFromDCache register. 
  * 
  * IMPORTANT: Following does not currently support misaligned reads or writes
  *
  */
class dCache extends Module {
  // All results from exec will be entered through here
  val resultsFromExec = IO(ComposableIO(new resultToMemAccess))
  val resultsToCommit = IO(ComposableIO(new resultFromDCache))

  object instructionTypes {
    // If there is cache-miss for an atomic instruction, then
    // it will be broken to two u-codes atomicReadPart and
    // atomicWritePart
    val noMemoryOperation :: write :: read :: atomic :: atomicReadPart :: atmoicWritePart :: Nil = Enum(6)
  }

  object missStates {
    val hit :: miss :: handlingMiss :: Nil = Enum(3)
  }

  // If at the moment a new instruction enters through resultsFromExec
  // the instruction currently occupying waitOnCacheRead will be moved
  // to stalledResultsFromDCache, then, the instruction currently being
  // fired from resultsFromExec will be moved to stalledResultsFromExec
  //
  // This register will also be occupyied by an instruction if an instruction
  // occuying resultsFromDCache is a cache-miss (Does not include to writes 
  // and peripheral accesses).
  //
  // Once the stalledResultsFromDCache is empty, the instruction occupying
  // stalledResultFromExec will move on to waitOnCacheRead.
  val stalledResultFromExec = RegInit(Valid(resultsFromExec.bits.cloneType).Lit(_.valid -> false.B))

  // All instructions fired from resultsFromExec will eventually reach here
  // and in the next cycle will move on to either resultsFromDCache or
  // stalledResultsFromDCache (or both).
  // 
  // When stalledResultsFromExec is empty, the instruction from resultsFromExec
  // will occupy waitOnCacheRead, otherwise it will be instruction from 
  // stalledResultsFromExec.
  // 
  // No instructions will be occupied by waitOnCacheRead if the current instruction
  // occupying resultsFromDCache is a cache-miss due to atomic or load targeting
  // main memory. Or the current instruction occupying waitOnCacheRead has to be
  // moved to stalledResultsFromDCache becasue of a stall on resultsToCommit interface
  val waitOnCacheRead = RegInit(Valid(resultsFromExec.bits.cloneType).Lit(_.valid -> false.B))

  // When we detect an stall on resultsToCommit interface, the instruction currently
  // occupying waitOnCacheRead will be stored here until stall on resultsToCommit
  // interface has been handled
  //
  // However in the event that there is cache-miss on an atomic instruction and there
  // are no stalls detected on resultsToCommit interface, then the instruction will be
  // broken down to two u-codes (read and write), read part will be stored, Same story
  // when recovering from a stall on resultsToCommit and the current instruction in 
  // stalledResultsFromDcache is an atomic-instruction and is also a cache-miss.
  //
  // For any instruction that resides in stalledResultsFromDCache, it must continously
  // observe cacheWrite interface for the instructions targeted data and catch it
  val stalledResultsFromDCache = RegInit(Valid(new Bundle {
    val instruction = resultsToCommit.bits.cloneType
    val instructionType = instructionTypes.noMemoryOperation.cloneType
    val missState = missStates.hit.cloneType
  }).Lit(_.valid -> false.B))

  // Final destination of all the instructions arriving to this module. If it is
  // a cache miss (because of a load instruction), then instruction will be stalled
  // until cache-miss is handled. The instruction must catch its corresponding data
  // from cacheWrite interface.
  //
  // Store instructions will be ready to commit reagardless miss status and will
  // trigger a cache line fetch. Cache writes will not happen in this situation.
  // 
  // Only when the complete cacheline of the miss is written to cache will the 
  // instruction will be a hit.
  val resultsFromDCache = RegInit(stalledResultsFromDCache.cloneType.Lit(_.valid -> false.B))

  
}