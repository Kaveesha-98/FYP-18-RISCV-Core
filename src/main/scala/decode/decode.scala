package pipeline.decode

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._
import pipeline.decode.constants._
import pipeline.decode.utils._

/** definition of all ports can be found here */
import pipeline.configuration.coreConfiguration._
import pipeline.ports._

/**
  * Functionality - Must communicate the pc of the first instruction to execute
  * through from Fetch.
  * 
  * Details about the IO can be found on common/ports.scala
  *
  */
class decode extends Module {
   /**
    * Inputs and Outputs of the module
    */
  val fromFetch       = IO(new recivInstrFrmFetch)        /** receives instructions from fetch and communicates the pc of the expected instruction */
  val branchRes       = IO(new branchResFrmDecode)        /** sends results of branched to fetch unit */
  val toExec          = IO(new pushInsToPipeline)         /** sends the decoded instruction to the next stage of the pipeline */
  val writeBackResult = IO(new pullCommitFrmRob)          /** receives results to write into the register file */
  val pcMatches = IO(Output(Bool()))
  val insState = IO(Output(UInt(3.W)))
//  val regFile = IO(Output(Vec(32, UInt(64.W))))
  /**
    * Internal of the module goes here
    */
/**----------------------------------------------------------------------------------------------------------------------*/
  /** Initializing a buffer for storing the input values from the fetch unit */
  val fetchIssueBuffer = RegInit(new Bundle{
    val pc          = UInt(dataWidth.W)
    val instruction = UInt(insAddrWidth.W)
  }.Lit(
    _.pc          -> initialPC.U,                         /** Initial value is set for the expectedPC */
    _.instruction -> 0.U
  ))

  /** Initializing a buffer for storing the output values to the exec unit */
  val decodeIssueBuffer = RegInit(new Bundle{
    val instruction = UInt(insAddrWidth.W)
    val pc          = UInt(dataWidth.W)
    val insType     = UInt(3.W)                           /** RISC-V instruction type: {R-type, I-type, S-type, B-type, U-type, J-type, N-type(for fence)} */
    val rs1         = new Src                             /** Register source 1 */
    val rs2         = rs1.cloneType                       /** Register source 2 */
    val write       = rs1.cloneType                       /** Register source 2 for store instructions */
    val immediate   = UInt(dataWidth.W)
  }.Lit(
    _.instruction   -> 0.U,
    _.pc            -> 0.U,
    _.insType       -> 0.U,
    _.rs1.data      -> 0.U,
    _.rs1.robAddr   -> 0.U,
    _.rs1.fromRob   -> false.B,
    _.rs2.data      -> 0.U,
    _.rs2.robAddr   -> 0.U,
    _.rs2.fromRob   -> false.B,
    _.write.data    -> 0.U,
    _.write.robAddr -> 0.U,
    _.write.fromRob -> false.B,
    _.immediate     -> 0.U
  ))

  val currentPrivilege = RegInit(MMODE.U(64.W))

  /** Initializing some intermediate wires */
  val opcode  = WireDefault(0.U(opcodeWidth.W))
  val rs1Addr = WireDefault(0.U(rs1Width.W))
  val rs2Addr = WireDefault(0.U(rs2Width.W))
  val rdAddr  = WireDefault(0.U(rdWidth.W))
  val fun3    = WireDefault(0.U(3.W))

  val validOutFetchBuf = WireDefault(false.B)         /** Valid signal of fetch buffer */
  val readyOutFetchBuf = WireDefault(false.B)         /** Ready signal of fetch buffer */

  val validOutDecodeBuf = WireDefault(false.B)        /** Valid signal of decode buffer */
  val readyOutDecodeBuf = WireDefault(false.B)        /** Ready signal of decode buffer */

  val insType   = WireDefault(0.U(3.W))
  val immediate = WireDefault(0.U(dataWidth.W))
  val rs1 = WireDefault(new Src().Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  val rs2 = WireDefault(rs1.cloneType.Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  val write = WireDefault(rs1.cloneType.Lit(
    _.data    -> 0.U,
    _.robAddr -> 0.U,
    _.fromRob -> false.B
  ))
  
  val valid = WireDefault(new Validity().Lit(
    _.rs1Data      -> true.B,
    _.rs1RobAddr   -> false.B,
    _.rs2Data      -> true.B,
    _.rs2RobAddr   -> false.B,
    _.writeData    -> true.B,
    _.writeRobAddr -> false.B
  ))

  val stalled   = WireDefault(false.B)                 /** Stall signal */
  val exception = RegInit(false.B)

  val ins = WireDefault(0.U(insAddrWidth.W))
  val pc  = WireDefault(0.U(dataWidth.W))
  val fReg = WireDefault(false.B)

  val expectedPC = WireDefault(0.U(dataWidth.W))      /** Expected pc value from the decode unit to the fetch unit */
  val branch = WireDefault(new Branch().Lit(
    _.isBranch      -> false.B,
    _.branchTaken   -> false.B,
    _.pc            -> 0.U,
    _.pcAfterBrnach -> 0.U,
    _.isRas         -> 0.B,
    _.rasAction     -> 0.U
  ))
  val isFetchBranch = WireDefault(false.B)            /** Branch instruction in fetchIssueBuffer */

  val isCSR        = WireDefault(false.B)
  val waitToCommit = WireDefault(false.B)
  val csrDone      = RegInit(false.B)
  val issueRobBuff = RegInit(0.U(robAddrWidth.W))
  val commitRobBuf = RegInit(0.U(robAddrWidth.W))
  val isfloat      = WireDefault(false.B)            //To use with fload and fstore

  /** Initializing states for the FSMs for fetch buffer and decode buffer */
  val emptyState :: fullState :: Nil = Enum(2)        /** States of FSM */
  val stateRegFetchBuf  = RegInit(emptyState)
  val stateRegDecodeBuf = RegInit(emptyState)

  //Adding csr for floating point
  val fcsr = Mem(1,UInt(32.W))       //Only first 8 bits will be used, the rest of the bits are reserved

  //For floating point 3 operand instructions
  val rs3ins   = RegInit(false.B)
  val rs3insNext = RegNext(rs3ins)
  val rs3insDecode = RegNext(rs3insNext)
  val isfmadd  = RegInit(false.B)
  val isfmsub  = RegInit(false.B)
  val isfnmadd = RegInit(false.B)
  val isfnmsub = RegInit(false.B)
  val rs3_rs3  = RegInit(0.U(5.W))     //To store the rs3 address of the 3 operand instruction
  val rs3_rd   = RegInit(0.U(5.W))     //To store the rd  address of the 3 operand instruction
  val rs3_rm   = RegInit(0.U(3.W))     //To store the rm  address of the 3 operand instruction
  //? Debug signals
  pcMatches := fromFetch.expected.pc === fromFetch.pc
  insState := 0.U

  val funct5List = List("b0000000".U,"b00001".U,"b00010".U,"b00011".U,"b01011".U, //FADD,FSUB,FMUL,FDIV,FSQRT
                            "b11000".U,"b11010".U)                                          //FCVT                   
  val funct5MatchFound = funct5List.map(funct5List => fromFetch.instruction(31,27) === funct5List).reduce(_ || _)
  // when(fromFetch.fired && readyOutFetchBuf && (fromFetch.expected.pc === fromFetch.pc)) { 
  //   fetchIssueBuffer.pc          := fromFetch.pc
  //   fetchIssueBuffer.instruction := fromFetch.instruction
  // }
  /** Storing instruction and pc in the fetch buffer */
  when( (fromFetch.fired && readyOutFetchBuf && (fromFetch.expected.pc === fromFetch.pc))) {       /** Data from the fetch unit is valid and fetch buffer is ready and the expected PC is matching */
    fetchIssueBuffer.pc          := fromFetch.pc
    // Adding the instruction modifier here.
    // For 3 operand instructions
    when(fromFetch.instruction(6,0) === fmadd.U || fromFetch.instruction(6,0) === fmsub.U || fromFetch.instruction(6,0) === fnmadd.U || fromFetch.instruction(6,0) === fnmsub.U){
      rs3ins := true.B
      //Rearranging the instruction to form two 2 operand instructions
      //Need a state transition
        when(fromFetch.instruction(6,0) === fmadd.U) { isfmadd := true.B}
          .otherwise{isfmadd := false.B}
        when(fromFetch.instruction(6,0) === fmsub.U) { isfmsub := true.B}
          .otherwise{isfmsub := false.B}
        when(fromFetch.instruction(6,0) === fnmadd.U){ isfnmadd := true.B}
          .otherwise{isfnmadd := false.B}
        when(fromFetch.instruction(6,0) === fnmsub.U){ isfnmsub := true.B}
          .otherwise{isfnmsub := false.B}
        
        //*All 3 operand instructions first converted to FMUL
        when(fromFetch.instruction(14,12) === "b111".U){
          //Replacing the dynamic rounding mode
          when(fcsr(0)(7,5) === "b111".U || fcsr(0)(7,5) === "b101".U || fcsr(0)(7,5) === "b110".U){
            fetchIssueBuffer.instruction := "x00000013".U //Pass a NOP instead
          } .otherwise{
            fetchIssueBuffer.instruction := Cat("b00010".U,fromFetch.instruction(26,15),fcsr(0)(7,5),fromFetch.instruction(11,0))
          }
        }.otherwise{
          fetchIssueBuffer.instruction := Cat("b00010".U,fromFetch.instruction(26,0))
        }

        rs3_rs3  := fromFetch.instruction(31,27)
        rs3_rd := fromFetch.instruction(11,7)
        rs3_rm  := fromFetch.instruction(14,12)
      
    } .elsewhen((funct5MatchFound &&  fromFetch.instruction(6,0) === fcomp.U)) {   
      rs3ins := false.B        
      //For computational (2 operand) and CVT 
      when(fromFetch.instruction(14,12) === "b111".U || fromFetch.instruction(14,12) === "b101".U || fromFetch.instruction(14,12) === "b110".U){ //rm = DYN || rm = for future use
        //Setting the illegal case to first rounding option
        when(fcsr(0)(7,5) === "b111".U){      //rm in both fcsr and instruction is dynamic    
          fetchIssueBuffer.instruction := "x00000013".U //Pass a NOP instead
        } .elsewhen(fromFetch.instruction(14,12) === "b101".U || fromFetch.instruction(14,12) === "b110".U){       //Reserved for future use
          fetchIssueBuffer.instruction := "x00000013".U //Pass a NOP instead
        }.otherwise {
          fetchIssueBuffer.instruction := Cat(fromFetch.instruction(31,15),fcsr(0)(7,5),fromFetch.instruction(11,0))
        }
      } .otherwise{
        fetchIssueBuffer.instruction := fromFetch.instruction
      }
    }.otherwise {
      rs3ins := false.B    
      fetchIssueBuffer.instruction := fromFetch.instruction
    }
  }
  //* For 3 operand instructions
  when(rs3ins){
    //For the 2nd set of instruction
    //FADD  f(rd) = f(rd) + f(rs3)
    // when(isfmadd) {fetchIssueBuffer.instruction := Cat("b0000000".U,fromFetch.instruction(31,27),fromFetch.instruction(11,7)
    //                                                                 ,fromFetch.instruction(14,7),fcomp.U)}
    // //FSUB  f(rd) = f(rd) - f(rs3)
    // when(isfmsub) {fetchIssueBuffer.instruction := Cat("b0000100".U,fromFetch.instruction(31,27),fromFetch.instruction(11,7)
    //                                                                 ,fromFetch.instruction(14,7),fcomp.U)}
    // //FSUB, f(rd) = f(rs3) - f(rd)
    // when(isfnmadd){fetchIssueBuffer.instruction := Cat("b0000100".U,fromFetch.instruction(31,27),fromFetch.instruction(11,7),
    //                                                                 fromFetch.instruction(14,7),fcomp.U)}
    // //FNMADD  f(rd) = -f(rd) - f(rs3)                                                                       
    // when(isfnmsub){fetchIssueBuffer.instruction := Cat("b0000000".U,fromFetch.instruction(31,27),fromFetch.instruction(11,7)
    //                                                                 ,fromFetch.instruction(14,7),fcomp.U)}
    when (rs3_rm === "b111".U){ //rm = DYN
      // fetchIssueBuffer.instruction := Cat(fromFetch.instruction(31,15),fcsr(0)(7,5),fromFetch.instruction(11,0))
      when(isfmadd) {fetchIssueBuffer.instruction := Cat("b0000000".U, rs3_rs3, rs3_rd, fcsr(0)(7,5), rs3_rd, fcomp.U)}
      //FSUB  f(rd) = f(rd) - f(rs3)
      when(isfmsub) {fetchIssueBuffer.instruction := Cat("b0000100".U, rs3_rs3, rs3_rd, fcsr(0)(7,5), rs3_rd, fcomp.U)}
      //FSUB, f(rd) = f(rs3) - f(rd)
      when(isfnmadd){fetchIssueBuffer.instruction := Cat("b0000100".U, rs3_rs3, rs3_rd, fcsr(0)(7,5), rs3_rd, fcomp.U)}
      //FNMADD  f(rd) = -f(rd) - f(rs3)                                                                       
      when(isfnmsub){fetchIssueBuffer.instruction := Cat("b0000000".U, rs3_rs3, rs3_rd, fcsr(0)(7,5), rs3_rd, fcomp.U)}
    
    
    } .otherwise{
      when(isfmadd) {fetchIssueBuffer.instruction := Cat("b0000000".U, rs3_rs3, rs3_rd, rs3_rm, rs3_rd, fcomp.U)}
      //FSUB  f(rd) = f(rd) - f(rs3)
      when(isfmsub) {fetchIssueBuffer.instruction := Cat("b0000100".U, rs3_rs3, rs3_rd, rs3_rm, rs3_rd, fcomp.U)}
      //FSUB, f(rd) = f(rs3) - f(rd)
      when(isfnmadd){fetchIssueBuffer.instruction := Cat("b0000100".U, rs3_rs3, rs3_rd, rs3_rm, rs3_rd, fcomp.U)}
      //FNMADD  f(rd) = -f(rd) - f(rs3)                                                                       
      when(isfnmsub){fetchIssueBuffer.instruction := Cat("b0000000".U, rs3_rs3, rs3_rd, rs3_rm, rs3_rd, fcomp.U)}
    } 

    rs3ins := false.B
    isfmadd := false.B
    isfmsub := false.B
    isfnmadd := false.B
    isfnmsub := false.B
  }
  ins := fetchIssueBuffer.instruction 
  pc  := fetchIssueBuffer.pc

  /** Storing values to the decode buffer */
  when((validOutFetchBuf && readyOutDecodeBuf)) {             /** data from the fetch buffer is valid and decode buffer is ready */
    decodeIssueBuffer.instruction := ins
    decodeIssueBuffer.pc          := pc
    decodeIssueBuffer.insType     := insType
    decodeIssueBuffer.rs1         := rs1
    decodeIssueBuffer.rs2         := rs2
    decodeIssueBuffer.write       := write
    decodeIssueBuffer.immediate   := immediate
  } 
  // .elsewhen(rs3insDecode){
  //   decodeIssueBuffer.instruction := ins
  //   decodeIssueBuffer.pc          := pc
  //   decodeIssueBuffer.insType     := insType
  //   decodeIssueBuffer.rs1         := rs1
  //   decodeIssueBuffer.rs2         := rs2
  //   decodeIssueBuffer.write       := write
  //   decodeIssueBuffer.immediate   := immediate
  // }

  /** Assigning outputs */
  /**--------------------------------------------------------------------------------------------------------------------*/
  toExec.ready       := validOutDecodeBuf
  toExec.instruction := decodeIssueBuffer.instruction
  toExec.pc          := decodeIssueBuffer.pc
  toExec.src1        := decodeIssueBuffer.rs1
  toExec.src2        := decodeIssueBuffer.rs2
  toExec.writeData   := decodeIssueBuffer.write
  
  //* Modifying to facilitate 3 operand instructions
  fromFetch.ready          := readyOutFetchBuf && !(isFetchBranch && stateRegFetchBuf === fullState) && fromFetch.expected.valid  && !rs3ins
  fromFetch.expected.pc    := expectedPC
  fromFetch.expected.valid := !((isFetchBranch && stateRegFetchBuf === fullState) || (branch.isBranch && stateRegDecodeBuf === fullState))

  branchRes.ready         := branch.isBranch && toExec.fired

  branchRes.isBranch      := branch.isBranch
  branchRes.branchTaken   := branch.branchTaken
  branchRes.pc            := branch.pc
  branchRes.pcAfterBrnach := branch.pcAfterBrnach
  branchRes.rasAction     := branch.rasAction
  branchRes.isRas         := branch.isRas

  writeBackResult.ready := true.B
  /**--------------------------------------------------------------------------------------------------------------------*/

  

  opcode  := ins(6, 0)
  rs1Addr := ins(19, 15)
  rs2Addr := ins(24, 20)
  rdAddr  := ins(11, 7)
  fun3    := ins(14, 12)

  insType         := getInsType(opcode)                                                 /** Deciding the instruction type */
  immediate       := getImmediate(ins, insType)                                         /** Calculating the immediate value */
  branch.isBranch := decodeIssueBuffer.instruction(6,0) === jump.U || decodeIssueBuffer.instruction(6,0) === jumpr.U || decodeIssueBuffer.instruction(6,0) === cjump.U      /** Branch detection in decodeIssueBuffer */
  isFetchBranch   := opcode === jump.U || opcode === jumpr.U || opcode === cjump.U      /** Branch detection in fetchIssueBuffer */

  //Return Address stack signals
  val rdLink = decodeIssueBuffer.instruction(11,7) === 1.U || decodeIssueBuffer.instruction(11,7) === 5.U
  val rs1Link = decodeIssueBuffer.instruction(19,15) === 1.U || decodeIssueBuffer.instruction(19,15) === 5.U
  branch.isRas := (decodeIssueBuffer.instruction(6,0) === jump.U && (rdLink)) || (decodeIssueBuffer.instruction(6,0) === jumpr.U && (rdLink || rs1Link))
  // set ras action
  when(rs1Link && !rdLink){
    branch.rasAction := pop.U
  }.elsewhen(rdLink && !rs1Link){
    branch.rasAction := push.U
  }.elsewhen((rs1Link && rdLink) && decodeIssueBuffer.instruction(19,15) === decodeIssueBuffer.instruction(11,7)){
    branch.rasAction := push.U
  }.elsewhen((rdLink && rs1Link) && !(decodeIssueBuffer.instruction(19,15) === decodeIssueBuffer.instruction(11,7))) {
    branch.rasAction := popThenPush.U
  }

  //* FReg logic
  /**--------------------------------------------------------------------------------------------------------------------*/
  //fwrite logic v2.0
  when(writeBackResult.inst(6,2)===BitPat("b00001")){
	  fReg:= true.B
  }.elsewhen(writeBackResult.inst(6,2)===BitPat("b100??")){
	  fReg:= true.B
  }.elsewhen(writeBackResult.inst(6,2)===BitPat("b10100")){
	  when(writeBackResult.inst(31) && !writeBackResult.inst(28)){
	  	fReg:= false.B
	  }.otherwise{
	  	fReg:= true.B
	  }
  }.otherwise{
	  fReg:= false.B
  }


  /** Register File activities */
  /**--------------------------------------------------------------------------------------------------------------------*/

  /**                  Register File
  |---------------------------------------------------|
  | validBit | register data | valdiBit | ROB address |
  |---------------------------------------------------|
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |          |               |          |             |
  |---------------------------------------------------|
   */

  /** Valid bits for each register */
  val validBit = RegInit(VecInit(Seq.fill(regCount)(1.U(1.W))))
  validBit(0) := 1.U
  // For floating point
  val validBitF = RegInit(VecInit(Seq.fill(regCount)(1.U(1.W))))

  /** Initializing the Register file */
  val registerFile = Mem(regCount, UInt(dataWidth.W))
  registerFile(0) := 0.U
  // For floating point, due to single precision 
  val registerFileF = Mem(regCount, UInt(32.W))

  /** Initializing the ROB address table of register file */
  val robFile = Mem(regCount, UInt(robAddrWidth.W))
  // For floating point
  val robFileF = Mem(regCount, UInt(robAddrWidth.W))

  /** Setting rs1 properties */
  //For floating point
  val isFCVT = ins(31,25) ==="b1101000".U && opcode === fcomp.U
  val isFMVxw  = ins(31,25) ==="b1111000".U && opcode === fcomp.U

  when(opcode === auipc.U || opcode === jump.U || opcode === system.U) {
    rs1.data         := pc
    rs1.robAddr      := 0.U
    valid.rs1Data    := true.B
    valid.rs1RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === rops.U || opcode === iops.U || opcode === rops32.U || opcode === iops32.U || opcode === cjump.U || opcode === jumpr.U || opcode === amos.U || opcode === fload.U || opcode === fstore.U || (isFMVxw || isFCVT) ) {
    rs1.data         := registerFile(rs1Addr)
    rs1.robAddr      := robFile(rs1Addr)
    when(stateRegDecodeBuf === fullState) {                                                                   /** Check dependencies in adjacent instrucitons */
      valid.rs1Data    := rs1Addr =/= decodeIssueBuffer.instruction(11,7) && (validBit(rs1Addr).asBool || (writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
      valid.rs1RobAddr := rs1Addr =/= decodeIssueBuffer.instruction(11,7) && ((~validBit(rs1Addr)).asBool && !(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
      
    }.elsewhen(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U && writeBackResult.inst(6,0) =/= fstore.U){
      //*Here if the data is present in writeback take it directly
      rs1.data         := writeBackResult.writeBackData
      valid.rs1Data := true.B
      valid.rs1RobAddr := false.B
    }.otherwise {
      valid.rs1Data    := validBit(rs1Addr).asBool
      valid.rs1RobAddr := (~validBit(rs1Addr)).asBool
    }
    when(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U && writeBackResult.inst(6,0) =/= fstore.U){
      rs1.data         := writeBackResult.writeBackData
    }
  }
  //For floating point
  //Add, sub, mul, div, sqrt, min , max; read from freg
  //For 3 source instr, first time rs1, second time rs3
  //For sign read from freg
  //MV ending .W read from freg
  //For compare read from freg
  //If class read form freg
  //If CVT ends in .S read from freg, ends in .W or .L read form integer reg
  //MV ending .X read from integer
  //Both fload, fstore read form integer file
  //First to work with Freg reads
  
  when(getInsType(opcode) === ftype.U && !( ((isFMVxw) || isFCVT))) {
    rs1.data         := registerFileF(rs1Addr)
    rs1.robAddr      := robFileF(rs1Addr)
    when(stateRegDecodeBuf === fullState) { 
      //* Checking with the next instruction                                                                  /** Check dependencies in adjacent instrucitons */
      valid.rs1Data    := (rs1Addr =/= decodeIssueBuffer.instruction(11,7) && (validBitF(rs1Addr).asBool || (writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr)  && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U )))
      valid.rs1RobAddr := (rs1Addr =/= decodeIssueBuffer.instruction(11,7) && ((~validBitF(rs1Addr)).asBool && !(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U )))
      
      //Added Freg check for good measure
    }.elsewhen(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr)&& (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U ){
      //*Here if the data is present in writeback take it directly and next instruction is not available yet
      rs1.data          := writeBackResult.writeBackData
      valid.rs1Data     := true.B
      valid.rs1RobAddr  := false.B
      //Added Freg check for good measure
    }.otherwise {
      valid.rs1Data    := validBitF(rs1Addr).asBool
      valid.rs1RobAddr := (~validBitF(rs1Addr)).asBool
    }
    when(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr)&& writeBackResult.inst(6,0) =/= fstore.U ){
      rs1.data         := writeBackResult.writeBackData
    }
  } 
  // .elsewhen(getInsType(opcode) === ftype.U ) { 
  //   //For MV.X , CVT._.W or CVT._.L, fload and fstore
  //   //fload condition now works with load
  //   rs1.data         := registerFile(rs1Addr)
  //   rs1.robAddr      := robFile(rs1Add
  //   when(stateRegDecodeBuf === fullState) 
  //     /** Check dependencies in adjacent instrucitons */
  //     valid.rs1Data    := rs1Addr =/= decodeIssueBuffer.instruction(11,7) && (validBit(rs1Addr).asBool || (writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U ))
  //     valid.rs1RobAddr := rs1Addr =/= decodeIssueBuffer.instruction(11,7) && ((~validBit(rs1Addr)).asBool && !(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U ))
  //     rs1.data          := writeBackResult.writeBackData
  //   }.elsewhen(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U ){
  //     //*Here if the data is present in writeback take it directly and next instruction is not available y
  //     rs1.data          := writeBackResult.writeBackData
  //     valid.rs1Data     := true.B
  //     valid.rs1RobAddr  := false.B
  //   }.otherwise
  //     valid.rs1Data    := validBit(rs1Addr).asBool
  //     valid.rs1RobAddr := (~validBit(rs1Addr)).asBool
  //   }
  //   when(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs1Addr) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U ){
  //     rs1.data         := writeBackResult.writeBackDa
  //   }
  // }

  /** Setting rs2 properties */
  when(opcode === jumpr.U) {
    rs2.data         := pc
    rs2.robAddr      := 0.U
    valid.rs2Data    := true.B
    valid.rs2RobAddr := false.B
  }
  when(opcode === jump.U || opcode === system.U) {
    rs2.data         := 4.U
    rs2.robAddr      := 0.U
    valid.rs2Data    := true.B
    valid.rs2RobAddr := false.B
  }
  when(opcode === load.U || opcode === store.U || opcode === iops.U || opcode === iops32.U || opcode === auipc.U || opcode === lui.U) {
    rs2.data         := immediate
    rs2.robAddr      := 0.U
    valid.rs2Data    := true.B
    valid.rs2RobAddr := false.B
  }
  when(opcode === cjump.U || opcode === rops.U || opcode === rops32.U) {
    rs2.data         := registerFile(rs2Addr)
    rs2.robAddr      := robFile(rs2Addr)
    when(stateRegDecodeBuf === fullState) {                                                                   /** Check dependencies in adjacent instrucitons */
      valid.rs2Data    := rs2Addr =/= decodeIssueBuffer.instruction(11,7) && (validBit(rs2Addr).asBool || (writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
      valid.rs2RobAddr := rs2Addr =/= decodeIssueBuffer.instruction(11,7) && ((~validBit(rs2Addr)).asBool && !(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
    }.elsewhen(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U){
      rs2.data         := writeBackResult.writeBackData
      valid.rs2Data := true.B
      valid.rs2RobAddr := false.B
    }.otherwise {
      valid.rs2Data    := validBit(rs2Addr).asBool
      valid.rs2RobAddr := (~validBit(rs2Addr)).asBool
    }
    when(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U){
      rs2.data         := writeBackResult.writeBackData
    }
  }

  /** Setting rs2 properties for store instructions */
  when(opcode === store.U || opcode === amos.U) {
    write.data         := registerFile(rs2Addr)
    write.robAddr      := robFile(rs2Addr)
    when(stateRegDecodeBuf === fullState) {                                                                   /** Check dependencies in adjacent instrucitons */
      valid.writeData := rs2Addr =/= decodeIssueBuffer.instruction(11, 7) && (validBit(rs2Addr).asBool || (writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
      valid.writeRobAddr := rs2Addr =/= decodeIssueBuffer.instruction(11, 7) && ((~validBit(rs2Addr)).asBool && !(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U))
    }.elsewhen(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U){
      write.data         := writeBackResult.writeBackData
      valid.writeData := true.B
      valid.writeRobAddr := false.B
    }.otherwise {
      valid.writeData := validBit(rs2Addr).asBool
      valid.writeRobAddr := (~validBit(rs2Addr)).asBool
    }
    when(writeBackResult.fired === 1.U && !validBit(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U){
      write.data         := writeBackResult.writeBackData
    }
  }
  //For floating point, fstore
  when(opcode === fstore.U) {
    write.data         := registerFileF(rs2Addr)
    write.robAddr      := robFileF(rs2Addr)
    when(stateRegDecodeBuf === fullState) {                                                                   /** Check dependencies in adjacent instrucitons */
      valid.writeData := rs2Addr =/= decodeIssueBuffer.instruction(11, 7) && (validBitF(rs2Addr).asBool || (writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg))
      valid.writeRobAddr := rs2Addr =/= decodeIssueBuffer.instruction(11, 7) && ((~validBitF(rs2Addr)).asBool && !(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg))
    }.elsewhen(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg){
      write.data         := writeBackResult.writeBackData
      valid.writeData := true.B
      valid.writeRobAddr := false.B
    }.otherwise {
      valid.writeData := validBitF(rs2Addr).asBool
      valid.writeRobAddr := (~validBitF(rs2Addr)).asBool
    }
    when(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg){
      write.data         := writeBackResult.writeBackData
    }
  }
  
  //Add, sub, mul, div min , max; read from freg; 
  //For 3 source instr, first time rs2, second time rob from previous one.
  //For sign read from freg
  //For compare read from freg
  //If class read this entry is zero
  //For CVT this entry is zero. As the instruction is passed to Exec.
  //MV ending .W this entry is zero
  //MV ending .X this entry is zero
  //for sqrt this is zero.
  //For fload this is Iimediate
  //For fstore this is Simmediate
  //*For FCLASS, FMV rs2-> 0, not required; but functionally not a problem
  //*For FCVT the rs2 function is different
  //For MV, CLASS, SQRT, rs2 is zero
  when(getInsType(opcode) === ftype.U || opcode === fload.U || opcode === fstore.U){ //For fload and fstore not included in ftype 
    when(ins(31,25) === BitPat("b111?000") ||  //For MV and class
        ins(31,25) ==="b0101100".U ||          //For sqrt
        ins(31,25) === BitPat("b110?000") ){   //For CVT
      rs2.data         := 0.U
      rs2.robAddr      := 0.U
      valid.rs2Data    := true.B
      valid.rs2RobAddr := false.B
    } .elsewhen(opcode === fload.U || opcode === fstore.U){
      rs2.data         := immediate
      rs2.robAddr      := 0.U
      valid.rs2Data    := true.B
      valid.rs2RobAddr := false.B
    } .otherwise{
      rs2.data         := registerFileF(rs2Addr)
      rs2.robAddr      := robFileF(rs2Addr)
      when(stateRegDecodeBuf === fullState) {                                                                   /** Check dependencies in adjacent instrucitons */
        valid.rs2Data    := rs2Addr =/= decodeIssueBuffer.instruction(11,7) && (validBitF(rs2Addr).asBool || (writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg ))
        valid.rs2RobAddr := rs2Addr =/= decodeIssueBuffer.instruction(11,7) && ((~validBitF(rs2Addr)).asBool && !(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg ))
      }.elsewhen(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr)){
        rs2.data         := writeBackResult.writeBackData
        valid.rs2Data := true.B
        valid.rs2RobAddr := false.B
      }.otherwise {

        valid.rs2Data    := validBitF(rs2Addr).asBool
        valid.rs2RobAddr := (~validBitF(rs2Addr)).asBool
      }
      when(writeBackResult.fired === 1.U && !validBitF(writeBackResult.inst(11,7)).asBool && (writeBackResult.inst(11,7) === rs2Addr) && (fReg) && (robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) && writeBackResult.inst(6,0) =/= fstore.U && fReg ){
        rs2.data         := writeBackResult.writeBackData
      }
    }
  }
  
  
  /** Register writing */
  // For floating point
  when (getInsType(writeBackResult.inst(6,0)) === ftype.U || writeBackResult.inst(6,0) === "b0000111".U || writeBackResult.inst(6,0) === "b0100111".U) { //For fload and fstore not included in ftype
    when(fReg){   //Some instructions write in fRegs
      when(writeBackResult.fired === 1.U && validBitF(writeBackResult.inst(11,7)) === 0.U && writeBackResult.inst(6,0) =/= fstore.U ) {
        registerFileF(writeBackResult.inst(11,7))  := writeBackResult.writeBackData
        when(robFileF(writeBackResult.inst(11,7)) === writeBackResult.robAddr) {
          validBitF(writeBackResult.inst(11,7))    := 1.U
          // Capturing the flags of the operation
          fcsr(0) := Cat(fcsr(0)(31,5),(writeBackResult.fFlags | fcsr(0)(4,0)))
        }
      }
    } .otherwise{                 //Some instructions write in integer regs
      when(writeBackResult.fired === 1.U && validBit(writeBackResult.inst(11,7)) === 0.U && writeBackResult.inst(11,7) =/= 0.U && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U) {
        registerFile(writeBackResult.inst(11,7))  := writeBackResult.writeBackData
        when(robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) {
          validBit(writeBackResult.inst(11,7))    := 1.U
          // Capturing the flags of the operation
          fcsr(0) := Cat(fcsr(0)(31,5),(writeBackResult.fFlags | fcsr(0)(4,0)))
        }
      }
    }
  } .otherwise{
    when(writeBackResult.fired === 1.U && validBit(writeBackResult.inst(11,7)) === 0.U && writeBackResult.inst(11,7) =/= 0.U && writeBackResult.inst(6,0) =/= store.U && writeBackResult.inst(6,0) =/= cjump.U) {
      registerFile(writeBackResult.inst(11,7))  := writeBackResult.writeBackData
      when(robFile(writeBackResult.inst(11,7)) === writeBackResult.robAddr) {
        validBit(writeBackResult.inst(11,7))    := 1.U
      }
    }
  }
  // For floating point
  when (getInsType(writeBackResult.inst(6,0)) === ftype.U || writeBackResult.inst(6,0) === "b0000111".U || writeBackResult.inst(6,0) === "b0100111".U) { //For fload and fstore not included in ftype
    when(writeBackResult.fired === 1.U && validBitF(writeBackResult.inst(11,7)) === 0.U) {
      commitRobBuf           := writeBackResult.robAddr
    }
  } .otherwise{
    when(writeBackResult.fired === 1.U && validBit(writeBackResult.inst(11,7)) === 0.U && writeBackResult.inst(11,7) =/= 0.U) {
      commitRobBuf           := writeBackResult.robAddr
    }
  }

  /** Rob File writing and deasserting valid bit for rd */
  when((decodeIssueBuffer.insType === rtype.U || decodeIssueBuffer.insType === utype.U || decodeIssueBuffer.insType === itype.U || decodeIssueBuffer.insType === jtype.U) && decodeIssueBuffer.instruction(11,7) =/= 0.U) {
    when(toExec.fired) {
      robFile(decodeIssueBuffer.instruction(11,7))     := toExec.robAddr
      validBit(decodeIssueBuffer.instruction(11,7))    := 0.U
      issueRobBuff                                     := toExec.robAddr
    }
  }
  //For floating point
  //If CVT ends in .S write to to integer reg, ends in .W or .L write to freg
  //If MV.X write to integer reg
  //If class write on integer reg
  //The ftype doesnot contain fload and fstore
  when(decodeIssueBuffer.insType === ftype.U || decodeIssueBuffer.instruction(6,0) === fload.U || decodeIssueBuffer.instruction(6,0) === fstore.U){
    val dfunct7 = decodeIssueBuffer.instruction(31,25)
    //    FCVT._.S                    FCLASS.S & FM.X            FCMP
    when(dfunct7 === "b1100000".U || dfunct7 === "b1110000".U || dfunct7 === "b1010000".U){
      when(toExec.fired) {
        robFile(decodeIssueBuffer.instruction(11,7))     := toExec.robAddr
        validBit(decodeIssueBuffer.instruction(11,7))    := 0.U
        issueRobBuff                                     := toExec.robAddr
      }
    }.otherwise{
      when(toExec.fired ) {
        robFileF(decodeIssueBuffer.instruction(11,7))     := toExec.robAddr
        validBitF(decodeIssueBuffer.instruction(11,7))    := 0.U
        issueRobBuff                                     := toExec.robAddr
      }
    }
  }

  /**--------------------------------------------------------------------------------------------------------------------*/

  when(stateRegFetchBuf === fullState && !isCSR) {
    stalled       := !((valid.rs1Data || valid.rs1RobAddr) && (valid.rs2RobAddr || valid.rs2Data) && (valid.writeData || valid.writeRobAddr)) || (isFetchBranch && !(valid.rs1Data && valid.rs2Data)) /** stall signal for FSM */
    rs1.fromRob   := !valid.rs1Data && valid.rs1RobAddr
    rs2.fromRob   := !valid.rs2Data && valid.rs2RobAddr
    write.fromRob := !valid.writeData && valid.writeRobAddr
  }

  /** FSM for ready valid interface of fetch buffer */
  /** -------------------------------------------------------------------------------------------------------------------*/
  switch(stateRegFetchBuf) {
    // -> Empty state - No valid entry in FetchIssueBuffer
    is(emptyState) {
      validOutFetchBuf := false.B // -> No valid entry in FetchIssueBuffer
      readyOutFetchBuf := true.B // -> Since FetchIssueBuffer is empty we can accept a new one
      when(fromFetch.fired && fromFetch.pc === fromFetch.expected.pc) {
        // -> Accepting a new instruction from fetch
        stateRegFetchBuf := fullState
      }
    }
    is(fullState) {
      when(writeBackResult.fired && writeBackResult.execptionOccured) {
        stateRegFetchBuf := emptyState
      }.otherwise{
        when(stalled || (isCSR && !csrDone)) {
          validOutFetchBuf := false.B
          readyOutFetchBuf := false.B
        } otherwise {
          validOutFetchBuf := !csrDone
          when(readyOutDecodeBuf) {
            // -> sedning entry to decodeIssueBuffer
            // -> Not system stuff
            readyOutFetchBuf := true.B
            when(!fromFetch.fired || fromFetch.pc =/= fromFetch.expected.pc) {
              // -> No new empty from Fetch
              stateRegFetchBuf := Mux(rs3ins,fullState,emptyState)
              // stateRegFetchBuf := emptyState
            }
          } otherwise {
            // -> .Stall in issuing instruction in decodeIssueBuf
            readyOutFetchBuf := false.B
          }
        }
      }
    }
  }
  /** -------------------------------------------------------------------------------------------------------------------*/

  /** FSM for ready valid interface of decode buffer */
  /** -------------------------------------------------------------------------------------------------------------------*/
  switch(stateRegDecodeBuf) {
    is(emptyState) {
      validOutDecodeBuf := false.B
      readyOutDecodeBuf := true.B
      when(validOutFetchBuf) {
        stateRegDecodeBuf := fullState
      }
    }
    is(fullState) {
      when(writeBackResult.fired && writeBackResult.execptionOccured) {
        stateRegDecodeBuf := emptyState
      } .otherwise {
        validOutDecodeBuf := true.B
        when(toExec.fired) {
          readyOutDecodeBuf := true.B
          when(!validOutFetchBuf) {
            stateRegDecodeBuf := emptyState
          }
        } otherwise {
          readyOutDecodeBuf := false.B
        }
      }
    }
  }
  /** -------------------------------------------------------------------------------------------------------------------*/

  /** Branch handling */
  /**  ------------------------------------------------------------------------------------------------------------------*/
  def getResult(pattern: (BitPat, UInt), prev: UInt) = pattern match {
    case (bitpat, result) => Mux(decodeIssueBuffer.instruction === bitpat, result, prev)
  }

  val targetReg = RegInit(0.U(dataWidth.W))

  when(branch.isBranch && isFetchBranch) {
    /* val branchTaken = Seq(
      decodeIssueBuffer.rs1.data === decodeIssueBuffer.rs2.data,
      decodeIssueBuffer.rs1.data =/= decodeIssueBuffer.rs2.data,
      decodeIssueBuffer.rs1.data.asSInt < decodeIssueBuffer.rs2.data.asSInt,
      decodeIssueBuffer.rs1.data.asSInt >= decodeIssueBuffer.rs2.data.asSInt,
      decodeIssueBuffer.rs1.data < decodeIssueBuffer.rs2.data,

      decodeIssueBuffer.rs1.data >= decodeIssueBuffer.rs2.data,

    ).zip(Seq(0, 1, 4, 5, 6, 7).map(i => i.U === decodeIssueBuffer.instruction(14, 12))
    ).map(condAndMatch => condAndMatch._1 && condAndMatch._2).reduce(_ || _) */
    val branchTaken = (VecInit.tabulate(4)(_ match {
      case 0 => decodeIssueBuffer.rs1.data =/= decodeIssueBuffer.rs2.data
      case 2 => decodeIssueBuffer.rs1.data.asSInt >= decodeIssueBuffer.rs2.data.asSInt
      case 3 => decodeIssueBuffer.rs1.data >= decodeIssueBuffer.rs2.data
      case _ => false.B
    })(decodeIssueBuffer.instruction(14, 13)).asUInt) === decodeIssueBuffer.instruction(12)

    val branchNextAddress = Mux(branchTaken, decodeIssueBuffer.pc + decodeIssueBuffer.immediate, decodeIssueBuffer.pc + 4.U)

    /* val target = Seq(
      BitPat("b?????????????????????????1101111") -> (decodeIssueBuffer.pc + decodeIssueBuffer.immediate), /** jal */
      BitPat("b?????????????????????????1100111") -> (decodeIssueBuffer.rs1.data + decodeIssueBuffer.immediate), /** jalr */
      BitPat("b?????????????????????????1100011") -> branchNextAddress, /** branches */
    ).foldRight(decodeIssueBuffer.pc + 4.U)(getResult) */

    /* VecInit.tabulate(4)(_ match {
      case 0 => branchNextAddress
      case 1 => (decodeIssueBuffer.rs1.data + decodeIssueBuffer.immediate)
      case _ => (decodeIssueBuffer.pc + decodeIssueBuffer.immediate)
    })(decodeIssueBuffer.instruction(3, 2)) */
    val target = Mux(decodeIssueBuffer.instruction(6, 4) === "b110".U, 
    VecInit.tabulate(4)(_ match {
      case 0 => branchNextAddress
      case 1 => (decodeIssueBuffer.rs1.data + decodeIssueBuffer.immediate)
      case _ => (decodeIssueBuffer.pc + decodeIssueBuffer.immediate)
    })(decodeIssueBuffer.instruction(3, 2)), decodeIssueBuffer.pc + 4.U
    )

    targetReg := target

    branch.branchTaken := branchTaken || decodeIssueBuffer.instruction(6,0) === jump.U || decodeIssueBuffer.instruction(6,0) === jumpr.U

    branch.pc := decodeIssueBuffer.pc
    branch.pcAfterBrnach := target
  }
  /**--------------------------------------------------------------------------------------------------------------------*/

  val delayReg = RegInit(0.U(64.W))

  /** CSR handling */
  /**--------------------------------------------------------------------------------------------------------------------*/
  isCSR := (opcode === system.U) && (fun3 =/= 0.U)//  && (stateRegFetchBuf === fullState)

  val robEmpty = IO(Input(Bool()))
  waitToCommit := ((issueRobBuff =/= commitRobBuf) && toExec.fired && stateRegDecodeBuf === fullState && !csrDone) || !robEmpty

//  val csrFile = Mem(csrRegCount, UInt(dataWidth.W))

  val ustatus = Mem(1, UInt(dataWidth.W))
  val utvec = Mem(1, UInt(dataWidth.W))
  val uepc = Mem(1, UInt(dataWidth.W))
  val ucause = Mem(1, UInt(dataWidth.W))
  val scounteren = Mem(1, UInt(dataWidth.W))
  val satp = Mem(1, UInt(dataWidth.W))
  val mstatus = Mem(1, UInt(dataWidth.W))
  val misa = Mem(1, UInt(dataWidth.W))
  val medeleg = Mem(1, UInt(dataWidth.W))
  val mideleg = Mem(1, UInt(dataWidth.W))
  val mie = Mem(1, UInt(dataWidth.W))
  val mtvec = Mem(1, UInt(dataWidth.W))
  val mcounteren = Mem(1, UInt(dataWidth.W))
  val mscratch = Mem(1, UInt(dataWidth.W))
  val mepc = Mem(1, UInt(dataWidth.W))
  val mcause = Mem(1, UInt(dataWidth.W))
  val mtval = Mem(1, UInt(dataWidth.W))
  val mip = Mem(1, UInt(dataWidth.W))
  val pmpcfg0 = Mem(1, UInt(dataWidth.W))
  val pmpaddr0 = Mem(1, UInt(dataWidth.W))
  val mvendorid = Mem(1, UInt(dataWidth.W))
  val marchid = Mem(1, UInt(dataWidth.W))
  val mimpid = Mem(1, UInt(dataWidth.W))
  val mhartid = Mem(1, UInt(dataWidth.W))

//  csrFile(MSTATUS.U) := (csrFile(MSTATUS.U) & "h0000000000001800".U) | "h0000002200000000".U
  // mstatus(0) := (mstatus(0) & "h0000000000001800".U) | "h0000000a00000000".U
  when(reset.asBool) {
    mstatus(0) := UMODE.U
    mscratch(0) := 0.U
  }
  misa(0) := "b100000001000100000001".U(64.W) + (2.U(64.W) << 62)

  val csrReadData = WireDefault(0.U(dataWidth.W))
  switch(immediate) {
    // is("h000".U) { csrReadData := ustatus(0) }
    // is("h005".U) { csrReadData := utvec(0) }
    // is("h041".U) { csrReadData := uepc(0) }
    // is("h042".U) { csrReadData := ucause(0) }
    // is("h106".U) { csrReadData := scounteren(0) }
    // is("h180".U) { csrReadData := satp(0) }
    is("h300".U) { csrReadData := mstatus(0) }
    is("h301".U) { csrReadData := misa(0) }
    // is("h302".U) { csrReadData := medeleg(0) }
    // is("h303".U) { csrReadData := mideleg(0) }
    is("h304".U) { csrReadData := mie(0) }
    is("h305".U) { csrReadData := mtvec(0) }
    // is("h306".U) { csrReadData := mcounteren(0) }
    is("h340".U) { csrReadData := mscratch(0) }
    is("h341".U) { csrReadData := mepc(0) }
    is("h342".U) { csrReadData := mcause(0) }
    is("h343".U) { csrReadData := mtval(0) }
    is("h344".U) { csrReadData := mip(0) }
    // is("h3a0".U) { csrReadData := pmpcfg0(0) }
    // is("h3b0".U) { csrReadData := pmpaddr0(0) }
    // is("hf11".U) { csrReadData := mvendorid(0) }
    // is("hf12".U) { csrReadData := marchid(0) }
    // is("hf13".U) { csrReadData := mimpid(0) }
    // is("hf14".U) { csrReadData := mhartid(0) }
    //*floating point
    is("h1".U) { csrReadData := fcsr(0)(4,0) }
    is("h2".U) { csrReadData := fcsr(0)(7,5) }
    is("h3".U) { csrReadData := fcsr(0) }
  }

  val csrWriteData = registerFile(rs1Addr)
  val csrWriteImmediate = Cat(0.U((27 + 32).W), rs1Addr)// rs1Addr & "h0000_0000_0000_001f".U
  val csrWriteOut = IO(Output(csrWriteImmediate.cloneType))
  csrWriteOut := csrWriteImmediate
  delayReg := Mux(!csrDone, csrReadData, delayReg)
  when(
    ((RegNext((isCSR && !waitToCommit) && ((isCSR && !waitToCommit) =/= RegNext(isCSR && !waitToCommit, false.B)), false.B)) ||
    (RegNext(RegNext(fromFetch.fired, false.B), false.B) && RegNext(isCSR, false.B) && robEmpty && RegNext(RegNext(isCSR, false.B), false.B)))
    && (stateRegDecodeBuf === emptyState) && robEmpty
  ) {
//    val csrReadData  = Mux(immediate === "h301".U , "h101101".U, csrFile(immediate))
    
    when(rdAddr =/= 0.U) {   //* Added so don't change if csr is done
      registerFile(rdAddr) := delayReg
    }
    def mstatusWrite(x: UInt) = Cat(mstatus(0)(63, 13), x(12, 0))

    when(ins(19, 15).orR || !ins(13).asBool){
      switch(fun3) {
        is("b001".U) {
  //        csrFile(immediate) := csrWriteData
          switch(immediate) {
            is("h000".U) { ustatus(0) := csrWriteData }
            is("h005".U) { utvec(0) := csrWriteData }
            is("h041".U) { uepc(0) := csrWriteData }
            is("h042".U) { ucause(0) := csrWriteData }
            is("h106".U) { scounteren(0) := csrWriteData }
            is("h180".U) { satp(0) := csrWriteData }
            is("h300".U) { mstatus(0) := mstatusWrite(csrWriteData) }
            is("h301".U) { misa(0) := csrWriteData }
            is("h302".U) { medeleg(0) := csrWriteData }
            is("h303".U) { mideleg(0) := csrWriteData }
            is("h304".U) { mie(0) := csrWriteData }
            is("h305".U) { mtvec(0) := csrWriteData }
            is("h306".U) { mcounteren(0) := csrWriteData }
            is("h340".U) { mscratch(0) := csrWriteData }
            is("h341".U) { mepc(0) := csrWriteData }
            is("h342".U) { mcause(0) := csrWriteData }
            is("h343".U) { mtval(0) := csrWriteData }
            is("h344".U) { mip(0) := csrWriteData }
            is("h3a0".U) { pmpcfg0(0) := csrWriteData }
            is("h3b0".U) { pmpaddr0(0) := csrWriteData }
            is("hf11".U) { mvendorid(0) := csrWriteData }
            is("hf12".U) { marchid(0) := csrWriteData }
            is("hf13".U) { mimpid(0) := csrWriteData }
            is("hf14".U) { mhartid(0) := csrWriteData }
            //* floating point 
            is("h1".U) { fcsr(0) := Cat(fcsr(0)(31,5),csrWriteData(4,0))}
            is("h2".U) { fcsr(0) := Cat(fcsr(0)(31,8),csrWriteData(2,0),fcsr(0)(4,0))}
            is("h3".U) { fcsr(0) := Cat(fcsr(0)(31,8),csrWriteData(7,0)) }
          }
        }
        is("b010".U) {
  //        csrFile(immediate) := csrReadData | csrWriteData
          switch(immediate) {
            is("h000".U) { ustatus(0)     := csrReadData | csrWriteData }
            is("h005".U) { utvec(0)       := csrReadData | csrWriteData }
            is("h041".U) { uepc(0)        := csrReadData | csrWriteData }
            is("h042".U) { ucause(0)      := csrReadData | csrWriteData }
            is("h106".U) { scounteren(0)  := csrReadData | csrWriteData }
            is("h180".U) { satp(0)        := csrReadData | csrWriteData }
            is("h300".U) { mstatus(0) := mstatusWrite(csrReadData | csrWriteData) }
            is("h301".U) { misa(0)        := csrReadData | csrWriteData }
            is("h302".U) { medeleg(0)     := csrReadData | csrWriteData }
            is("h303".U) { mideleg(0)     := csrReadData | csrWriteData }
            is("h304".U) { mie(0)         := csrReadData | csrWriteData }
            is("h305".U) { mtvec(0)       := csrReadData | csrWriteData }
            is("h306".U) { mcounteren(0)  := csrReadData | csrWriteData }
            is("h340".U) { mscratch(0)    := csrReadData | csrWriteData }
            is("h341".U) { mepc(0)        := csrReadData | csrWriteData }
            is("h342".U) { mcause(0)      := csrReadData | csrWriteData }
            is("h343".U) { mtval(0)       := csrReadData | csrWriteData }
            is("h344".U) { mip(0)         := csrReadData | csrWriteData }
            is("h3a0".U) { pmpcfg0(0)     := csrReadData | csrWriteData }
            is("h3b0".U) { pmpaddr0(0)    := csrReadData | csrWriteData }
            is("hf11".U) { mvendorid(0)   := csrReadData | csrWriteData }
            is("hf12".U) { marchid(0)     := csrReadData | csrWriteData }
            is("hf13".U) { mimpid(0)      := csrReadData | csrWriteData }
            is("hf14".U) { mhartid(0)     := csrReadData | csrWriteData }
            //* floating point 
            is("h1".U) { fcsr(0) := Cat(fcsr(0)(31,5),(csrReadData | csrWriteData)(4,0))}
            is("h2".U) { fcsr(0) := Cat(fcsr(0)(31,8),(csrReadData | csrWriteData)(2,0),fcsr(0)(4,0))}
            is("h3".U) { fcsr(0) := Cat(fcsr(0)(31,8),(csrReadData | csrWriteData)(7,0)) }
          }
        }
        is("b011".U) {

  //        csrFile(immediate) := csrReadData & ~csrWriteData
          switch(immediate) {
            is("h000".U) { ustatus(0)     := csrReadData & ~csrWriteData }
            is("h005".U) { utvec(0)       := csrReadData & ~csrWriteData }
            is("h041".U) { uepc(0)        := csrReadData & ~csrWriteData }
            is("h042".U) { ucause(0)      := csrReadData & ~csrWriteData }
            is("h106".U) { scounteren(0)  := csrReadData & ~csrWriteData }
            is("h180".U) { satp(0)        := csrReadData & ~csrWriteData }
            is("h300".U) { mstatus(0) := mstatusWrite(csrReadData & ~csrWriteData) }
            is("h301".U) { misa(0)        := csrReadData & ~csrWriteData }
            is("h302".U) { medeleg(0)     := csrReadData & ~csrWriteData }
            is("h303".U) { mideleg(0)     := csrReadData & ~csrWriteData }
            is("h304".U) { mie(0)         := csrReadData & ~csrWriteData }
            is("h305".U) { mtvec(0)       := csrReadData & ~csrWriteData }
            is("h306".U) { mcounteren(0)  := csrReadData & ~csrWriteData }
            is("h340".U) { mscratch(0)    := csrReadData & ~csrWriteData }
            is("h341".U) { mepc(0)        := csrReadData & ~csrWriteData }
            is("h342".U) { mcause(0)      := csrReadData & ~csrWriteData }
            is("h343".U) { mtval(0)       := csrReadData & ~csrWriteData }
            is("h344".U) { mip(0)         := csrReadData & ~csrWriteData }
            is("h3a0".U) { pmpcfg0(0)     := csrReadData & ~csrWriteData }
            is("h3b0".U) { pmpaddr0(0)    := csrReadData & ~csrWriteData }
            is("hf11".U) { mvendorid(0)   := csrReadData & ~csrWriteData }
            is("hf12".U) { marchid(0)     := csrReadData & ~csrWriteData }
            is("hf13".U) { mimpid(0)      := csrReadData & ~csrWriteData }
            is("hf14".U) { mhartid(0)     := csrReadData & ~csrWriteData }
          }

        }
        is("b101".U) {
  //        csrFile(immediate) := csrWriteImmediate
          switch(immediate) {
            is("h000".U) { ustatus(0)     := csrWriteImmediate }
            is("h005".U) { utvec(0)       := csrWriteImmediate }
            is("h041".U) { uepc(0)        := csrWriteImmediate }
            is("h042".U) { ucause(0)      := csrWriteImmediate }
            is("h106".U) { scounteren(0)  := csrWriteImmediate }
            is("h180".U) { satp(0)        := csrWriteImmediate }
            is("h300".U) { mstatus(0) := mstatusWrite(csrWriteImmediate) }
            is("h301".U) { misa(0)        := csrWriteImmediate }
            is("h302".U) { medeleg(0)     := csrWriteImmediate }
            is("h303".U) { mideleg(0)     := csrWriteImmediate }
            is("h304".U) { mie(0)         := csrWriteImmediate }
            is("h305".U) { mtvec(0)       := csrWriteImmediate }
            is("h306".U) { mcounteren(0)  := csrWriteImmediate }
            is("h340".U) { mscratch(0)    := csrWriteImmediate }
            is("h341".U) { mepc(0)        := csrWriteImmediate }
            is("h342".U) { mcause(0)      := csrWriteImmediate }
            is("h343".U) { mtval(0)       := csrWriteImmediate }
            is("h344".U) { mip(0)         := csrWriteImmediate }
            is("h3a0".U) { pmpcfg0(0)     := csrWriteImmediate }
            is("h3b0".U) { pmpaddr0(0)    := csrWriteImmediate }
            is("hf11".U) { mvendorid(0)   := csrWriteImmediate }
            is("hf12".U) { marchid(0)     := csrWriteImmediate }
            is("hf13".U) { mimpid(0)      := csrWriteImmediate }
            is("hf14".U) { mhartid(0)     := csrWriteImmediate }
            //* floating point 
            is("h1".U) { fcsr(0) := Cat(fcsr(0)(31,5),csrWriteImmediate(4,0))}
            is("h2".U) { fcsr(0) := Cat(fcsr(0)(31,8),csrWriteImmediate(2,0),fcsr(0)(4,0))}
            is("h3".U) { fcsr(0) := Cat(fcsr(0)(31,8),(csrWriteImmediate)(7,0))  }
          }
        }
        is("b110".U) {
  //        csrFile(immediate) := csrReadData | csrWriteImmediate
          switch(immediate) {
            is("h000".U) { ustatus(0)     := csrReadData | csrWriteImmediate }
            is("h005".U) { utvec(0)       := csrReadData | csrWriteImmediate }
            is("h041".U) { uepc(0)        := csrReadData | csrWriteImmediate }
            is("h042".U) { ucause(0)      := csrReadData | csrWriteImmediate }
            is("h106".U) { scounteren(0)  := csrReadData | csrWriteImmediate }
            is("h180".U) { satp(0)        := csrReadData | csrWriteImmediate }
            is("h300".U) { mstatus(0) := mstatusWrite(csrReadData | csrWriteImmediate) }
            is("h301".U) { misa(0)        := csrReadData | csrWriteImmediate }
            is("h302".U) { medeleg(0)     := csrReadData | csrWriteImmediate }
            is("h303".U) { mideleg(0)     := csrReadData | csrWriteImmediate }
            is("h304".U) { mie(0)         := csrReadData | csrWriteImmediate }
            is("h305".U) { mtvec(0)       := csrReadData | csrWriteImmediate }
            is("h306".U) { mcounteren(0)  := csrReadData | csrWriteImmediate }
            is("h340".U) { mscratch(0)    := csrReadData | csrWriteImmediate }
            is("h341".U) { mepc(0)        := csrReadData | csrWriteImmediate }
            is("h342".U) { mcause(0)      := csrReadData | csrWriteImmediate }
            is("h343".U) { mtval(0)       := csrReadData | csrWriteImmediate }
            is("h344".U) { mip(0)         := csrReadData | csrWriteImmediate }
            is("h3a0".U) { pmpcfg0(0)     := csrReadData | csrWriteImmediate }
            is("h3b0".U) { pmpaddr0(0)    := csrReadData | csrWriteImmediate }
            is("hf11".U) { mvendorid(0)   := csrReadData | csrWriteImmediate }
            is("hf12".U) { marchid(0)     := csrReadData | csrWriteImmediate }
            is("hf13".U) { mimpid(0)      := csrReadData | csrWriteImmediate }
            is("hf14".U) { mhartid(0)     := csrReadData | csrWriteImmediate }
          }
        }
        is("b111".U) {

  //        csrFile(immediate) := csrReadData & ~csrWriteImmediate
          switch(immediate) {
            is("h000".U) { ustatus(0)     := csrReadData & ~csrWriteImmediate }
            is("h005".U) { utvec(0)       := csrReadData & ~csrWriteImmediate }
            is("h041".U) { uepc(0)        := csrReadData & ~csrWriteImmediate }
            is("h042".U) { ucause(0)      := csrReadData & ~csrWriteImmediate }
            is("h106".U) { scounteren(0)  := csrReadData & ~csrWriteImmediate }
            is("h180".U) { satp(0)        := csrReadData & ~csrWriteImmediate }
            is("h300".U) { 
              mstatus(0) := mstatusWrite(csrReadData & ~csrWriteImmediate)
              println(csrWriteImmediate)
            }
            is("h301".U) { misa(0)        := csrReadData & ~csrWriteImmediate }
            is("h302".U) { medeleg(0)     := csrReadData & ~csrWriteImmediate }
            is("h303".U) { mideleg(0)     := csrReadData & ~csrWriteImmediate }
            is("h304".U) { mie(0)         := csrReadData & ~csrWriteImmediate }
            is("h305".U) { mtvec(0)       := csrReadData & ~csrWriteImmediate }
            is("h306".U) { mcounteren(0)  := csrReadData & ~csrWriteImmediate }
            is("h340".U) { mscratch(0)    := csrReadData & ~csrWriteImmediate }
            is("h341".U) { mepc(0)        := csrReadData & ~csrWriteImmediate }
            is("h342".U) { mcause(0)      := csrReadData & ~csrWriteImmediate }
            is("h343".U) { mtval(0)       := csrReadData & ~csrWriteImmediate }
            is("h344".U) { mip(0)         := csrReadData & ~csrWriteImmediate }
            is("h3a0".U) { pmpcfg0(0)     := csrReadData & ~csrWriteImmediate }
            is("h3b0".U) { pmpaddr0(0)    := csrReadData & ~csrWriteImmediate }
            is("hf11".U) { mvendorid(0)   := csrReadData & ~csrWriteImmediate }
            is("hf12".U) { marchid(0)     := csrReadData & ~csrWriteImmediate }
            is("hf13".U) { mimpid(0)      := csrReadData & ~csrWriteImmediate }
            is("hf14".U) { mhartid(0)     := csrReadData & ~csrWriteImmediate }
            //* floating point 
            is("h1".U) { fcsr(0) := Cat(fcsr(0)(31,5),(csrReadData & ~csrWriteImmediate)(4,0))}
            is("h2".U) { fcsr(0) := Cat(fcsr(0)(31,8),(csrReadData & ~csrWriteImmediate)(2,0),fcsr(0)(4,0))}
            is("h3".U) { fcsr(0) := Cat(fcsr(0)(31,8),(csrReadData & ~csrWriteImmediate)(7,0))  }
          }

        }
      }
    }
    csrDone := true.B
    commitRobBuf := issueRobBuff + 1.U // making them unequal
  }

  when(csrDone && fromFetch.fired && fromFetch.pc === fromFetch.expected.pc) {
    csrDone := false.B
  }
  /**--------------------------------------------------------------------------------------------------------------------*/

  /** Exceptions handling */
  /** -------------------------------------------------------------------------------------------------------------------- */

  val mretCall = RegInit(false.B)
  when(writeBackResult.fired && writeBackResult.execptionOccured && !mretCall && (writeBackResult.mcause =/= 3.U)) {
    exception := true.B

    mepc(0) := writeBackResult.mepc
    when(writeBackResult.mcause === 11.U) {
      when(currentPrivilege === MMODE.U) {
        mcause(0) := writeBackResult.mcause
      }.otherwise {
        mcause(0) := writeBackResult.mcause - 3.U
      }
    }.otherwise { // ebreak
      mcause(0) := writeBackResult.mcause
    }
    mstatus(0) := currentPrivilege | ((mstatus(0)&"h08".U(64.W)) << 4)

    currentPrivilege := MMODE.U
  }.elsewhen(writeBackResult.fired && writeBackResult.execptionOccured && mretCall) {
    // mret call
    currentPrivilege := mstatus(0)
    // expectedPC := mepc(0)
    mstatus(0) := (mstatus(0) & (~(3.U(64.W) << 11))) | ("h080".U(64.W)) | ((mstatus(0)&"h080".U(64.W)) >> 4)
  }

  when(exception && fromFetch.fired && fromFetch.pc === fromFetch.expected.pc) {
    exception := false.B
  }
  /**--------------------------------------------------------------------------------------------------------------------*/

  when(exception) {
    expectedPC := mtvec(0)
  }.elsewhen(opcode === system.U && fun3 === 0.U && immediate === 770.U ) {
    //currentPrivilege := mstatus(0)
    expectedPC := mepc(0)
    //mstatus(0) := (mstatus(0) & (~(3.U(64.W) << 11))) | ("h080".U(64.W)) | ((mstatus(0)&"h080".U(64.W)) >> 4)
    /* when(fromFetch.fired && fromFetch.pc === fromFetch.expected.pc) {
      mstatus(0) := UMODE.U

    } */
  }.elsewhen(branch.isBranch && isFetchBranch) {
    expectedPC := targetReg
  }.otherwise {
    expectedPC := pc + 4.U
  }


  //debug
  val decodePC = IO(Output(UInt(64.W)))
  decodePC := decodeIssueBuffer.pc;
  val decodeIns = IO(Output(UInt(32.W)))
  decodeIns := decodeIssueBuffer.instruction

  val noCall :: callDecode :: callIssue :: callCommit :: Nil = Enum(4)

  val procCalls = RegInit(noCall)
  switch(procCalls) {
    is(noCall) { 
      when(fromFetch.fired && (fromFetch.instruction(6, 0) === "b1110011".U) && (fromFetch.instruction(14, 12) === 0.U)) {
        procCalls := callDecode
        mretCall := (fromFetch.instruction(31, 20) === "b001100000010".U)
      } 
    }
    is(callDecode) {
      fromFetch.ready := false.B
      when(toExec.fired && (toExec.instruction(6, 0) === "b1110011".U) && (toExec.instruction(14, 12) === 0.U)) { procCalls := callIssue }
    }
    is(callIssue) {
      fromFetch.ready := false.B
      when(writeBackResult.fired && (writeBackResult.execptionOccured)) { procCalls := noCall }
    }
  }

  val allowInterrupt = IO(Output(Bool()))
  allowInterrupt := mstatus(0)(3).asBool && mie(0)(7).asBool && (procCalls === noCall) && (ins(6, 0) =/= "h73".U)
//  var i = 0;
//
//  for(i <- 0 to 31){
//    regFile(i) := registerFile(i)
//  }
//  regFile := registerFile


}

// object DecodeUnit extends App{
//   println("Generating the DecodeUnit hardware")
//   emitVerilog(new decode(), Array("--target-dir", "generated"))
// }