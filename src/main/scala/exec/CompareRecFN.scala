
/*============================================================================

This Chisel source file is part of a pre-release version of the HardFloat IEEE
Floating-Point Arithmetic Package, by John R. Hauser (with some contributions
from Yunsup Lee and Andrew Waterman, mainly concerning testing).

Copyright 2010, 2011, 2012, 2013, 2014, 2015, 2016 The Regents of the
University of California.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice,
    this list of conditions, and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions, and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 3. Neither the name of the University nor the names of its contributors may
    be used to endorse or promote products derived from this software without
    specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS", AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, ARE
DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

=============================================================================*/
//this code is edited
package hardfloat

import chisel3._

class CompareRecFN(expWidth: Int, sigWidth: Int) extends RawModule
{
    val io = IO(new Bundle {
		val a = Input(new RawFloat(expWidth, sigWidth))
        val b = Input(new RawFloat(expWidth, sigWidth))
        val signaling = Input(Bool())
        val lt = Output(Bool())
        val eq = Output(Bool())
        val gt = Output(Bool())
        val exceptionFlags = Output(Bits(5.W))
    })

    val ordered = ! io.a.isNaN && ! io.b.isNaN
    val bothInfs  = io.a.isInf  && io.b.isInf
    val bothZeros = io.a.isZero && io.b.isZero
    val eqExps = (io.a.sExp === io.b.sExp)
    val common_ltMags =
        (io.a.sExp < io.b.sExp) || (eqExps && (io.a.sig < io.b.sig))
    val common_eqMags = eqExps && (io.a.sig === io.b.sig)

    val ordered_lt =
        ! bothZeros &&
            ((io.a.sign && ! io.b.sign) ||
                 (! bothInfs &&
                      ((io.a.sign && ! common_ltMags && ! common_eqMags) ||
                           (! io.b.sign && common_ltMags))))
    val ordered_eq =
        bothZeros || ((io.a.sign === io.b.sign) && (bothInfs || common_eqMags))

    val invalid =
        isSigNaNRawFloat(io.a) || isSigNaNRawFloat(io.b) ||
            (io.signaling && ! ordered)

    io.lt := ordered && ordered_lt
    io.eq := ordered && ordered_eq
    io.gt := ordered && ! ordered_lt && ! ordered_eq
    io.exceptionFlags := invalid ## 0.U(4.W)
}

