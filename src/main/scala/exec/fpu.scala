package hardfloat

import chisel3._
import chisel3.util._




class fpu(expWidth: Int, sigWidth: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
	val inst = Input(UInt(32.W))
	val result = Output(UInt(64.W))
	val ef = Output(UInt(5.W))
	val toRobReady = Input(Bool())
    val insState = Output(UInt(3.W))
    val inValid = Input(Bool())    
	val inReady = Output(Bool())      
	val valid_div = Output(Bool())  
	val valid_sqrt = Output(Bool())
  })
  
  /*
  Important Notes
  
  1. Detect tininess is set to 1 because Detecting tininess after rounding is usually slightly better - Hardfloat Verilog Doc 6.1
  2. Currently, the only valid value for the options parameter is zero for DivSqrtRawFN_small - Hardfloat Verilog Doc 9.8
  3. exception flags order :- invalid , Divide Zero , overflow , underflow , inexact 
  4. CompareRecFN :- if signaling is true, a signaling comparison is done, meaning that and invalid ef is raised if either operand is any kind of NaN. 
	 If signaling is false, a quiet comparison is done, meaning that quiet NaNs do not cuase an invalid ef. - chapter 9.9
  
  */
  
  val recoded_result_addsub = Wire(UInt(33.W))
  val recoded_result_mul = Wire(UInt(33.W))
  val recoded_result_divsqrt = Wire(UInt(33.W))
  val recoded_result_cvt_sw = Wire(UInt(33.W))
  val recoded_result_cvt_sl = Wire(UInt(33.W))
  val recoded_result_muladd = WireInit(0.U(33.W))
  
  val result_addsub = Wire(UInt(32.W))
  val result_mul = Wire(UInt(32.W))
  val result_divsqrt = Wire(UInt(32.W))
  val result_cvt_sw = Wire(UInt(32.W))
  val result_cvt_sl = Wire(UInt(32.W))
  val result_muladd = Wire(UInt(32.W))
  
  val result_minmax = WireInit(0.U(32.W))
  val result_signinj = Wire(UInt(32.W))
  
  
  val ef_addsub = Wire(UInt(5.W))
  val ef_mul = Wire(UInt(5.W))
  val ef_divsqrt = Wire(UInt(5.W))
  val ef_compare = Wire(UInt(5.W))
  val ef_cvt_ws = Wire(UInt(5.W))
  val ef_cvt_ls = Wire(UInt(5.W))
  val ef_cvt_sw = Wire(UInt(5.W))
  val ef_cvt_sl = Wire(UInt(5.W))
  val ef_muladd = WireInit(0.U(5.W))

  
  // Using rawFloatFromFN function
  val raw_a = rawFloatFromFN(expWidth, sigWidth, io.a(31,0))
  val raw_b = rawFloatFromFN(expWidth, sigWidth, io.b(31,0))
  
  
  
  
  /*
  //black box fadd module , debug
  val add = Module(new addRecFN(expWidth, sigWidth))
  add.io.subOp := io.inst(27)
  add.io.a := recFNFromFN(expWidth,sigWidth,io.a(31,0))
  add.io.b := recFNFromFN(expWidth,sigWidth,io.b(31,0))
  add.io.roundingMode := io.inst(14,12)
  add.io.detectTininess := true.B
  recoded_result_addsub := add.io.out
  ef_addsub := add.io.exceptionFlags
  //
  */
  
  /*
  //debug
  val add = Module(new AddRecFN(expWidth, sigWidth))
  add.io.subOp := io.inst(27)
  add.io.a := recFNFromFN(expWidth,sigWidth,io.a(31,0))
  add.io.b := recFNFromFN(expWidth,sigWidth,io.b(31,0))
  add.io.roundingMode := io.inst(14,12)
  add.io.detectTininess := true.B
  recoded_result_addsub := add.io.out
  ef_addsub := add.io.exceptionFlags
  //
  */
  
  //FMUL.S
  val mulRawFN = Module(new MulRawFN(expWidth, sigWidth))

  mulRawFN.io.a := raw_a
  mulRawFN.io.b := raw_b

  val roundRawFNToRecFN_mul = Module(new RoundAnyRawFNToRecFN(expWidth,sigWidth+2,expWidth,sigWidth, 0))
  roundRawFNToRecFN_mul.io.invalidExc   := mulRawFN.io.invalidExc
  roundRawFNToRecFN_mul.io.infiniteExc  := false.B
  roundRawFNToRecFN_mul.io.in           := mulRawFN.io.rawOut
  roundRawFNToRecFN_mul.io.roundingMode := io.inst(14,12)
  roundRawFNToRecFN_mul.io.detectTininess := true.B
  recoded_result_mul            := roundRawFNToRecFN_mul.io.out
  ef_mul := roundRawFNToRecFN_mul.io.exceptionFlags

  //*For 3 operand instructions
  val rawMulRes = RegNext(mulRawFN.io.rawOut)
  val efMulRes = RegNext(ef_mul)
  val isThreeOp = RegInit(false.B)
  val isMulResNeg = RegInit(false.B)

  val insState = WireInit(0.U(3.W))
  io.insState := insState
  
  when(isThreeOp) {
    insState := 1.U
    when(io.toRobReady && io.inst(6,4) =/= "b100".U){
      insState := 3.U
      isThreeOp := false.B
      isMulResNeg := false.B
    }
  } .otherwise{
    insState := 2.U
    isThreeOp :=  io.inst(6,4) === "b100".U
    isMulResNeg := io.inst(6,3) === "b1001".U
  }

  //FADD.S , FSUB.S
  
  val addRawFN = Module(new AddRawFN(expWidth, sigWidth))

  addRawFN.io.subOp        := io.inst(27)
  addRawFN.io.b            := raw_b
  //addRawFN.io.b            := Mux(isThreeOp, rawMulRes, raw_b)
  addRawFN.io.a.isInf      := raw_a.isInf
  addRawFN.io.a.isNaN      := raw_a.isNaN
  addRawFN.io.a.isZero     := raw_a.isZero
  addRawFN.io.a.sExp       := raw_a.sExp
  addRawFN.io.a.sig        := raw_a.sig
  addRawFN.io.a.sign       := Mux(isThreeOp,Mux(isMulResNeg,!raw_a.sign,raw_a.sign) ,raw_a.sign)

  addRawFN.io.roundingMode := io.inst(14,12)
  
  val roundRawFNToRecFN_addsub = Module(new RoundAnyRawFNToRecFN(expWidth,sigWidth+2,expWidth,sigWidth, 0))
  roundRawFNToRecFN_addsub.io.invalidExc   := addRawFN.io.invalidExc
  roundRawFNToRecFN_addsub.io.infiniteExc  := false.B
  roundRawFNToRecFN_addsub.io.in           := addRawFN.io.rawOut
  roundRawFNToRecFN_addsub.io.roundingMode := io.inst(14,12)
  roundRawFNToRecFN_addsub.io.detectTininess := true.B
  recoded_result_addsub            := roundRawFNToRecFN_addsub.io.out
  ef_addsub := Mux(isThreeOp,roundRawFNToRecFN_addsub.io.exceptionFlags | efMulRes ,roundRawFNToRecFN_addsub.io.exceptionFlags)

  
  val divSqrtRawFN = Module(new DivSqrtRawFN_small(expWidth, sigWidth, 0))

  io.inReady := divSqrtRawFN.io.inReady            
  divSqrtRawFN.io.inValid      := io.inValid
  divSqrtRawFN.io.sqrtOp       := io.inst(30)
  divSqrtRawFN.io.a            := raw_a
  divSqrtRawFN.io.b            := raw_b
  divSqrtRawFN.io.roundingMode := io.inst(14,12)

  io.valid_div  := divSqrtRawFN.io.rawOutValid_div  
  io.valid_sqrt := divSqrtRawFN.io.rawOutValid_sqrt  
  
  val roundRawFNToRecFN_divsqrt = Module(new RoundAnyRawFNToRecFN(expWidth,sigWidth+2,expWidth,sigWidth, 0))
  roundRawFNToRecFN_divsqrt.io.invalidExc   := divSqrtRawFN.io.invalidExc
  roundRawFNToRecFN_divsqrt.io.infiniteExc  := divSqrtRawFN.io.infiniteExc
  roundRawFNToRecFN_divsqrt.io.in           := divSqrtRawFN.io.rawOut
  roundRawFNToRecFN_divsqrt.io.roundingMode := divSqrtRawFN.io.roundingModeOut
  roundRawFNToRecFN_divsqrt.io.detectTininess := true.B
  recoded_result_divsqrt            := roundRawFNToRecFN_divsqrt.io.out
  ef_divsqrt := roundRawFNToRecFN_divsqrt.io.exceptionFlags
  
  //FSGNJ.S , FSGNJN.S & FSGNJX.S
  when(io.inst(13)){
	result_signinj := (io.a(31) ^ io.b(31))##io.a(30,0) 
  }.otherwise{
	when(io.inst(12)){
		result_signinj := (!io.b(31))##io.a(30,0) 
	}.otherwise{
		result_signinj := (io.b(31))##io.a(30,0) 
	}
  }
  
  //FEQ.S , FLT.S , FLE.S , FMIN.S & FMAX.S
  //assume there is no notNaNboxed signaling NaNs because,
  //NaNboxed and notNaNboxed quiet NaNs can be detected because it's faction is not zero
  //NaNboxed signaling NaNs can be detected because it's fraction is not zero
  //not NaNboxed signaling NaNs canot be detected because it's fraction is zero
  //this all because a logic rawFloatFromFN.scala. go and see..
  
  //thise block doesnt adhere the need that "signaling NaN input set the invalid flag" spec requirement
  val compareRawFN = Module(new CompareRecFN(expWidth, sigWidth))
  compareRawFN.io.a := raw_a
  compareRawFN.io.b := raw_b
  compareRawFN.io.signaling := false.B
  ef_compare := compareRawFN.io.exceptionFlags
  
  val result_64_compare = WireInit(0.U(64.W))
  
  when(io.inst(31)){
	when(io.inst(13)){
		result_64_compare := (0.U(63.W))##compareRawFN.io.eq.asUInt()				//feq
	}.otherwise{
		when(io.inst(12)){
			result_64_compare := (0.U(63.W))##compareRawFN.io.lt.asUInt()			//flt
			ef_compare := (raw_a.isNaN || raw_b.isNaN) ## 0.U(4.W)
		}.otherwise{
			result_64_compare := (0.U(63.W))## (compareRawFN.io.eq || compareRawFN.io.lt)		//fle
			ef_compare := (raw_a.isNaN || raw_b.isNaN) ## 0.U(4.W)
		}
	}
  }.otherwise{
	/*
	when(io.inst(12)){
		when(compareRawFN.io.gt){
			result_minmax := io.a(31,0)
		}.otherwise{
			result_minmax := io.b(31,0)
		}
	}.otherwise{
		when(compareRawFN.io.gt){
			result_minmax := io.b(31,0)
		}.otherwise{
			result_minmax := io.a(31,0)
		}
	}
	*/
	when(raw_a.isNaN && raw_b.isNaN){
		result_minmax := "h7fc00000".U(32.W) //canonical NaN
	}.otherwise{
		when(raw_a.isNaN){
			result_minmax := io.b(31,0)
		}.elsewhen(raw_b.isNaN){
			result_minmax := io.a(31,0)
		}.otherwise{
			when(io.inst(12)){
				when(compareRawFN.io.gt){
					result_minmax := io.a(31,0)
				}.elsewhen(compareRawFN.io.lt){
					result_minmax := io.b(31,0)
				}.otherwise{
					result_minmax := (io.a(31) && io.b(31)) ## 0.U(31.W)
				}
			}.otherwise{
				when(compareRawFN.io.gt){
					result_minmax := io.b(31,0)
				}.elsewhen(compareRawFN.io.lt){
					result_minmax := io.a(31,0)
				}.otherwise{
					result_minmax := (io.a(31) || io.b(31)) ## 0.U(31.W)
				}
			}
		}
	}
	
  }
  
  //FCLASS.S
  val result_64_class = Wire(UInt(64.W))
  result_64_class := (0.U(54.W))##classifyRecFN(expWidth, sigWidth,raw_a)
  
  //FCVT.W.S , FCVT.WU.S
  val result_64_cvt_ws = Wire(UInt(64.W))
  val RawFNToIN_32 = Module(new RecFNToIN(expWidth, sigWidth,32))
  RawFNToIN_32.io.in := raw_a
  RawFNToIN_32.io.roundingMode := io.inst(14,12)
  RawFNToIN_32.io.signedOut := !io.inst(20)
  result_64_cvt_ws := Cat(Fill(32, RawFNToIN_32.io.out(31)),RawFNToIN_32.io.out)
  ef_cvt_ws := RawFNToIN_32.io.intExceptionFlags(1) ## 0.U(1.W) ## RawFNToIN_32.io.intExceptionFlags(2) ## 0.U(1.W) ## RawFNToIN_32.io.intExceptionFlags(0) 
  //there is a problem in the RecFNToIN block. invalid and overflow signal's positions shoud exchanged.
 
  
  //FCVT.L.S , FCVT.LU.S
  val result_64_cvt_ls = Wire(UInt(64.W))
  val RawFNToIN_64 = Module(new RecFNToIN(expWidth, sigWidth,64))
  RawFNToIN_64.io.in := raw_a
  RawFNToIN_64.io.roundingMode := io.inst(14,12)
  RawFNToIN_64.io.signedOut := !io.inst(20)
  result_64_cvt_ls := RawFNToIN_64.io.out
  ef_cvt_ls := RawFNToIN_64.io.intExceptionFlags(1) ## 0.U(1.W) ## RawFNToIN_64.io.intExceptionFlags(2) ## 0.U(1.W) ## RawFNToIN_64.io.intExceptionFlags(0)
  //there is a problem in the RecFNToIN block. invalid and overflow signal's positions shoud exchanged.
  
  //FCVT.S.W , FCVT.S.WU 
  val roundRawFNToRecFN_cvt_sw = Module(new RoundAnyRawFNToRecFN(expWidth-2,sigWidth+8,expWidth,sigWidth, 0))
  roundRawFNToRecFN_cvt_sw.io.invalidExc   := false.B
  roundRawFNToRecFN_cvt_sw.io.infiniteExc  := false.B
  roundRawFNToRecFN_cvt_sw.io.in           := rawFloatFromIN(!io.inst(20),io.a(31,0))
  roundRawFNToRecFN_cvt_sw.io.roundingMode := io.inst(14,12)
  roundRawFNToRecFN_cvt_sw.io.detectTininess := true.B
  recoded_result_cvt_sw           := roundRawFNToRecFN_cvt_sw.io.out
  ef_cvt_sw := roundRawFNToRecFN_cvt_sw.io.exceptionFlags
  
  //FCVT.S.L , FCVT.S.LU
  val roundRawFNToRecFN_cvt_sl = Module(new RoundAnyRawFNToRecFN(expWidth-1,sigWidth+40,expWidth,sigWidth, 0))
  roundRawFNToRecFN_cvt_sl.io.invalidExc   := false.B
  roundRawFNToRecFN_cvt_sl.io.infiniteExc  := false.B
  roundRawFNToRecFN_cvt_sl.io.in           := rawFloatFromIN(!io.inst(20),io.a)
  roundRawFNToRecFN_cvt_sl.io.roundingMode := io.inst(14,12)
  roundRawFNToRecFN_cvt_sl.io.detectTininess := true.B
  recoded_result_cvt_sl           := roundRawFNToRecFN_cvt_sl.io.out
  ef_cvt_sl := roundRawFNToRecFN_cvt_sl.io.exceptionFlags
  
  //FMADD.S , FMSUB.S , FNMSUB.S , FNMADD.S
  
  //FMV.W.X
  
  //FMV.X.W
  
  //FLW , FSW
		//handled outside the fpu
  
  //this need to be edited. sake of compilation of fpu
  
  result_addsub  := fNFromRecFN(expWidth,sigWidth,recoded_result_addsub)
  result_mul     := fNFromRecFN(expWidth,sigWidth,recoded_result_mul)
  result_divsqrt := fNFromRecFN(expWidth,sigWidth,recoded_result_divsqrt)
  
  /*
  when(io.inst(20) && io.a(63)){
	result_cvt_sw := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sw) + 1.U
	result_cvt_sl  := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sl) + 1.U
  }.otherwise{
	result_cvt_sw := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sw)
	result_cvt_sl  := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sl)
  }
  there is an issue when converting negative numbers to FN when the instructions are fcvt.s.wu and fcvt.s.lu 
  that is because block consider this negative integer as a positive number when that happens if that positive number is so
  large that cannot be represent as FN it should output 4f800000(largest FN number) but this module gives
  4f7fffff which 1 less thatn required that is because i add this when otherwise block.
  */
  result_cvt_sw  := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sw)
  result_cvt_sl  := fNFromRecFN(expWidth,sigWidth,recoded_result_cvt_sl)
  result_muladd  := fNFromRecFN(expWidth,sigWidth,recoded_result_muladd)
  
  when(io.inst(4)){
	when(io.inst(31,27)===BitPat("b0000?")){
		io.result := Cat(0.U(32.W),result_addsub)
		io.ef     := ef_addsub
	}.elsewhen(io.inst(31,27)===BitPat("b00010")){
		io.result := Cat(0.U(32.W),result_mul)
		io.ef     := ef_mul
	}.elsewhen(io.inst(31,27)===BitPat("b0?011")){
		io.result := Cat(0.U(32.W),result_divsqrt)
		io.ef     := ef_divsqrt
	}.elsewhen(io.inst(31,27)===BitPat("b00100")){
		io.result := Cat(0.U(32.W),result_signinj)
		io.ef     := 0.U(5.W)
	}.elsewhen(io.inst(31,27)===BitPat("b00101")){
		io.result := Cat(0.U(32.W),result_minmax)
		io.ef     := ef_compare 
	}.elsewhen(io.inst(31,27)===BitPat("b10100")){
		io.result := result_64_compare
		io.ef     := ef_compare
	}.elsewhen(io.inst(31,27)===BitPat("b11000")){
		when(io.inst(21)){
			io.result := result_64_cvt_ls
			io.ef     := ef_cvt_ls
		}.otherwise{
			io.result := result_64_cvt_ws
			io.ef     := ef_cvt_ws
		}
	}.elsewhen(io.inst(31,27)===BitPat("b11010")){
		when(io.inst(21)){
			io.result := Cat(0.U(32.W),result_cvt_sl)
			io.ef     := ef_cvt_sl
		}.otherwise{
			io.result := Cat(0.U(32.W),result_cvt_sw)
			io.ef     := ef_cvt_sw
		}
	}.elsewhen(io.inst(31,27)===BitPat("b11100")){
		when(io.inst(12)){
			io.result := result_64_class
			io.ef     := 0.U(5.W) 
		}.otherwise{
			io.result := Cat(Fill(32, io.a(31)),io.a(31,0))
			io.ef     := 0.U(5.W)
		}
	}.otherwise{
		io.result := Cat(0.U(32.W),io.a(31,0))
		io.ef     := 0.U(5.W)
	}
  } .elsewhen(io.inst(6,4) === "b100".U){
    io.result := Cat(0.U(32.W),result_mul)
    io.ef     := ef_mul
  }.otherwise{
	io.result := result_muladd
	io.ef     := ef_muladd
  }
  
  //io.result := Cat(0.U(32.W),result_addsub)
  //io.ef     := ef_addsub
   
  
  //fwrite logic v2.0
  // when(io.inst(6,2)===BitPat("b00001")){
	// io.fwrite := true.B
  // }.elsewhen(io.inst(6,2)===BitPat("b100??")){
	// io.fwrite := true.B
  // }.elsewhen(io.inst(6,2)===BitPat("b10100")){
	// when(io.inst(31) && !io.inst(28)){
	// 	io.fwrite := false.B
	// }.otherwise{
	// 	io.fwrite := true.B
	// }
  // }.otherwise{
	// io.fwrite := false.B
  // }
}

