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
import pipeline.decode.constants.opcode5MSBs

class dCacheRegisters extends Module {

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
    // 'fence' will belong to noMemoryOperation, memAccess will send
    // the fired signal only when all older writes are complete.
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

  val cache = Module(new dCacheRegisters)
  val cacheWrite = IO(Input(cache.cacheWrite.cloneType))

  // When address belongs to main memory it will fetch a whole cacheline and when
  // the instruction belongs to peripherals it will only fetch the amount required
  // the requesting instruction
  val fetchRequest = IO(ComposableIO(new Bundle {
    val address = UInt(XLEN.W)
    val instrucion = UInt(ILEN.W)
  }))
  fetchRequest.bits.address := Cat(resultsFromDCache.bits.instruction.address(XLEN-3),0.U(3.W))
  fetchRequest.bits.instrucion := resultsFromDCache.bits.instruction.instruction
  fetchRequest.ready := resultsFromDCache.valid && !resultsToCommit.ready && (resultsFromDCache.bits.missState === missStates.miss)

  // given a memory read instuction, checks whether it requires sign extention
  def needSignExtention(instruction: UInt) = !(instruction(14))

  // Takes the double-word targeted by the access. i.e. the double-word at address
  // address&(~7)
  //
  // Does right justification and sign extention as requested by the instruction
  def convertForRegisterFileFromByteAlignedData (data: UInt, instruction: UInt, address: UInt) = {
    // val bytesInDoubleWord = VecInit.tabulate(8)(i => data(i+7,i))
    val bitsToSignExtend = VecInit.tabulate(8)(i => data(i+7))
    val bitToSignExtend = VecInit.tabulate(3)(_ match {
      case 0 => VecInit.tabulate(8)(i => data(i+7))(address(2,0))
      case 1 => VecInit.tabulate(4)(i => data(i+15))(address(1,0))
      case 2 => VecInit.tabulate(2)(i => data(i+31))(address(0).asUInt)
    })(funct3Of(instruction)(1,0))
    // val signExtendedByte = Fill(8, Mux(needSignExtention(instruction), bitToSignExtend, 0.U(1.W)))
    val bitToFill = Mux(needSignExtention(instruction), bitToSignExtend, 0.U(1.W))
    Cat(
      Mux(funct3Of(instruction)==="b011".U(3.W), data(63,32), Fill(32, bitToFill)),
      Mux(funct3Of(instruction)(1,0)>"b01".U(2.W), VecInit.tabulate(3)(i => data(i+31,i+16))(address(1,0)), Fill(16, bitToFill)),
      Mux(funct3Of(instruction)(1,0)>="b01".U(2.W), VecInit.tabulate(7)(i => data(i+15,i+8))(address(2,0)), Fill(8, bitToFill)),
      VecInit.tabulate(8)(i => data(i+7,i))(address(2,0))
    )
  }

  val cacheLookUpResult = Wire(stalledResultsFromDCache.cloneType)
  cacheLookUpResult.valid := waitOnCacheRead.valid
  cacheLookUpResult.bits.instructionType := MuxLookup(opcode5BitsOf(waitOnCacheRead.bits.instruction), instructionTypes.noMemoryOperation, Seq(
    opcode5MSBs.load.U(5.W) -> instructionTypes.read,
    opcode5MSBs.store.U(5.W) -> instructionTypes.write,
    opcode5MSBs.amos.U(5.W) -> instructionTypes.atomic
  ))
  cacheLookUpResult.bits.missState := Mux(cache.lookUpResults.valid && (getTagOfAddress(waitOnCacheRead.bits.address) === cache.lookUpResults.tag), missStates.hit, missStates.miss)
  cacheLookUpResult.bits.instruction.address := waitOnCacheRead.bits.address
  cacheLookUpResult.bits.instruction.dataToRegisterFile := convertForRegisterFileFromByteAlignedData(
    Mux(
      resultsFromDCache.valid && (getDoubleWordTarget(resultsFromDCache.bits.instruction.address) === getDoubleWordTarget(waitOnCacheRead.bits.address)) && resultsFromDCache.bits.instruction.writeStrobe.andR,
      resultsFromDCache.bits.instruction.writeData,
      cache.lookUpResults.data
    ),waitOnCacheRead.bits.instruction, waitOnCacheRead.bits.address
  )
  cacheLookUpResult.bits.instruction.fwdAddr := waitOnCacheRead.bits.fwdAddr
  cacheLookUpResult.bits.instruction.instruction := waitOnCacheRead.bits.instruction
  cacheLookUpResult.bits.instruction.meta := waitOnCacheRead.bits.meta
  cacheLookUpResult.bits.instruction.nextPC := waitOnCacheRead.bits.nextPC
  cacheLookUpResult.bits.instruction.writeData := waitOnCacheRead.bits.writeData
  cacheLookUpResult.bits.instruction.writeStrobe := VecInit.tabulate(4)(_ match {
    case 0 => "h01".U(8.W)
    case 1 => "h03".U(8.W)
    case 2 => "h0f".U(8.W)
    case 3 => "hff".U(8.W)
  })(funct3Of(waitOnCacheRead.bits.instruction)(1,0))

  // reservation sets for semaphore instructions
  // we have two because the emulator has 2
  val reservationSet32bits = RegInit(Valid(new Bundle {
    val address = UInt(XLEN.W)
    val data = UInt(XLEN.W)
  }).Lit(_.valid -> false.B))
  val reservationSet64bits = RegInit(reservationSet32bits.cloneType.Lit(_.valid -> false.B))

  // The atmoic request can come from two places waitOnCacheRead and stalledResultsFromDCache. But
  // at any given time at most only one can have a request (of any kind)
  val atomicCalculationInputs = Wire(new Bundle {
    val src1 = UInt(XLEN.W)
    val src2 = UInt(XLEN.W)
    val instruction = UInt(ILEN.W)
  })

  // Getting the inputs for atomic calculation, we take the appropriate sign extended 32bit value
  // for word size requests
  when (stalledResultsFromDCache.valid) {
    atomicCalculationInputs.instruction := stalledResultsFromDCache.bits.instruction.instruction
    // 'dataToRegisterFile' should have byte aligned data, so we have to pick correct data for computation
    atomicCalculationInputs.src1 := convertForRegisterFileFromByteAlignedData(stalledResultsFromDCache.bits.instruction.dataToRegisterFile, stalledResultsFromDCache.bits.instruction.instruction, stalledResultsFromDCache.bits.instruction.address)
    // For word access atomics, we need to sign extend writeData for proper functionality
    atomicCalculationInputs.src2 := Cat(
      Mux(
        atomicInstructionIsWordAccess(stalledResultsFromDCache.bits.instruction.instruction), 
        Fill(32, stalledResultsFromDCache.bits.instruction.writeData(31)), 
        stalledResultsFromDCache.bits.instruction.writeData(63,32)
      ), 
      stalledResultsFromDCache.bits.instruction.writeData(31,0))
  }.otherwise {
    atomicCalculationInputs.instruction := waitOnCacheRead.bits.instruction
    // Only other source is directly from cache
    atomicCalculationInputs.src1 := convertForRegisterFileFromByteAlignedData(cache.lookUpResults.data, waitOnCacheRead.bits.instruction, waitOnCacheRead.bits.address)
    atomicCalculationInputs.src2 := Cat(
      Mux(
        atomicInstructionIsWordAccess(waitOnCacheRead.bits.instruction), 
        Fill(32, waitOnCacheRead.bits.writeData(31)), 
        waitOnCacheRead.bits.writeData(63,32)
      ), 
      waitOnCacheRead.bits.writeData(31,0))
  }
  // AMOMAX, AMOMIN, AMOMINU, AMOMAXU
  val unsigned63slt = atomicCalculationInputs.src1(62,0) < atomicCalculationInputs.src2(62,0)
  val compareResultSignedOrUnsignedLessThan = Mux(
    atomicCalculationInputs.src1(63) === atomicCalculationInputs.src2(63),
    unsigned63slt, Mux(atomicCalculationInputs.instruction(30), atomicCalculationInputs.src2(63), atomicCalculationInputs.src1(63))
  )
  val atomicCompareCalculation = Mux(compareResultSignedOrUnsignedLessThan ^ atomicCalculationInputs.instruction(29), atomicCalculationInputs.src1, atomicCalculationInputs.src2)

  // AMOADD, AMOXOR, AMOOR, AMOAND
  val atomicArithneticCalculation = VecInit.tabulate(4)(_ match {
    case 0 => atomicCalculationInputs.src1 + atomicCalculationInputs.src2
    case 1 => atomicCalculationInputs.src1 ^ atomicCalculationInputs.src2
    case 2 => atomicCalculationInputs.src1 | atomicCalculationInputs.src2
    case 3 => atomicCalculationInputs.src1 & atomicCalculationInputs.src2
  })

  // AMOADD, AMOXOR, AMOOR, AMOAND, AMOMAX, AMOMIN, AMOMINU, AMOMAXU
  val atomicCalculation = Mux(atomicCalculationInputs.instruction(31), atomicCompareCalculation, atomicArithneticCalculation)

  // This is the result from atomics that will be written to memory (in case of sc.*, only when it succeeds)
  val atomicResult = Mux(atomicCalculationInputs.instruction(28,27) === 0.U(2.W), atomicCalculation, atomicCalculationInputs.src2)

  // updating resultsFromDCache register
  //
  // This also drives the resultsToCommit interface
  //
  // read instructions are not ready until the corresponding cacheline has been
  // completely fetched. atomicWritePart micro-instrcution will not reach here.
  // atomicReadPart micro-instruction will never be commited. If there is an
  // atomic (complete) instrucion, it should always be a hit, hence, should always
  // be ready to commit. write instructions can be commited even when cache-miss.
  // memAccess will take care of writing to memory without writing to cache
  //
  // For debugging we might have to change write behaviour to only commit when
  // the whole cacheline has been fetched to cache.
  resultsToCommit.ready := resultsFromDCache.valid && MuxLookup(resultsFromDCache.bits.instructionType, true.B, Seq(
    instructionTypes.read -> (resultsFromDCache.bits.missState === missStates.hit),
    instructionTypes.atomicReadPart -> false.B
  ))
  resultsToCommit.bits := resultsFromDCache.bits.instruction

  when (resultsFromDCache.valid && !resultsToCommit.ready && (resultsFromDCache.bits.missState === missStates.miss)) {
    // There should be instructions that are awaiting for a cacheline
    // For now should ideally be atmoicReadPart and read cacheline misses
    when (cacheWrite.valid) {
      // We are now forwarding the relevant data to the register. We need to make sure
      // the addresses target the same double-word
      when (cacheWrite.bits.address(XLEN-1,3) === resultsFromDCache.bits.instruction.address(XLEN-1,3)) {
        resultsFromDCache.bits.instruction.dataToRegisterFile := convertForRegisterFileFromByteAlignedData(cacheWrite.bits.data, resultsFromDCache.bits.instruction.instruction, resultsFromDCache.bits.instruction.address)
        // we do not set it as hit, until the complete cache line has been fetched
      }
      when (cacheWrite.bits.last) {
        resultsFromDCache.bits.missState := missStates.hit
      }
    }
    when (resultsFromDCache.bits.missState === missStates.miss) {
      // In this state the d-cache is waiting for memAccess to accept the request to
      // fetch data from main memory or peripheral data
      when (fetchRequest.fired) {
        resultsFromDCache.bits.missState := missStates.handlingMiss
      }
    }
  }.elsewhen(resultsFromDCache.valid && (resultsFromDCache.bits.instructionType === instructionTypes.atomicReadPart)) {
    // atomic writePart should be in stalledResultsFromDCache

  }
}