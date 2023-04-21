# 1 "../../riscv-tests/isa/rv64ui/srai.S"
# 1 "<built-in>"
# 1 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ui/srai.S"
# See LICENSE for license details.

#*****************************************************************************
# srai.S
#-----------------------------------------------------------------------------

# Test srai instruction.


# 1 "../../riscv-tests/env/v/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/../encoding.h" 1
# 7 "../../riscv-tests/env/v/../p/riscv_test.h" 2
# 7 "../../riscv-tests/env/v/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ui/srai.S" 2
# 1 "../../riscv-tests/isa/macros/scalar/test_macros.h" 1






#-----------------------------------------------------------------------
# Helper macros
#-----------------------------------------------------------------------
# 20 "../../riscv-tests/isa/macros/scalar/test_macros.h"
# We use a macro hack to simpify code generation for various numbers
# of bubble cycles.
# 36 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# RV64UI MACROS
#-----------------------------------------------------------------------

#-----------------------------------------------------------------------
# Tests for instructions with immediate operand
#-----------------------------------------------------------------------
# 92 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Tests for an instruction with register operands
#-----------------------------------------------------------------------
# 120 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Tests for an instruction with register-register operands
#-----------------------------------------------------------------------
# 214 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test memory instructions
#-----------------------------------------------------------------------
# 347 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test jump instructions
#-----------------------------------------------------------------------
# 376 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# RV64UF MACROS
#-----------------------------------------------------------------------

#-----------------------------------------------------------------------
# Tests floating-point instructions
#-----------------------------------------------------------------------
# 735 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Pass and fail code (assumes test num is in gp)
#-----------------------------------------------------------------------
# 747 "../../riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test data section
#-----------------------------------------------------------------------
# 12 "../../riscv-tests/isa/rv64ui/srai.S" 2

.macro init; .endm
.text; .global _start

_start:
	j test_2

  #-------------------------------------------------------------
  # Arithmetic tests
  #-------------------------------------------------------------

  test_2: li gp, 2; li x1, ((0xffffff8000000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((0) | (-(((0) >> 11) & 1) << 11));; li x7, ((0xffffff8000000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_3: li gp, 3; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((1) | (-(((1) >> 11) & 1) << 11));; li x7, ((0xffffffffc0000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_4: li gp, 4; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((7) | (-(((7) >> 11) & 1) << 11));; li x7, ((0xffffffffff000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_5: li gp, 5; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((14) | (-(((14) >> 11) & 1) << 11));; li x7, ((0xfffffffffffe0000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_6: li gp, 6; li x1, ((0xffffffff80000001) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((31) | (-(((31) >> 11) & 1) << 11));; li x7, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_7: li gp, 7; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((0) | (-(((0) >> 11) & 1) << 11));; li x7, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_8: li gp, 8; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((1) | (-(((1) >> 11) & 1) << 11));; li x7, ((0x000000003fffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_9: li gp, 9; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((7) | (-(((7) >> 11) & 1) << 11));; li x7, ((0x0000000000ffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_10: li gp, 10; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((14) | (-(((14) >> 11) & 1) << 11));; li x7, ((0x000000000001ffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_11: li gp, 11; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((31) | (-(((31) >> 11) & 1) << 11));; li x7, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_12: li gp, 12; li x1, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((0) | (-(((0) >> 11) & 1) << 11));; li x7, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_13: li gp, 13; li x1, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((1) | (-(((1) >> 11) & 1) << 11));; li x7, ((0xffffffffc0c0c0c0) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_14: li gp, 14; li x1, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((7) | (-(((7) >> 11) & 1) << 11));; li x7, ((0xffffffffff030303) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_15: li gp, 15; li x1, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((14) | (-(((14) >> 11) & 1) << 11));; li x7, ((0xfffffffffffe0606) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_16: li gp, 16; li x1, ((0xffffffff81818181) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((31) | (-(((31) >> 11) & 1) << 11));; li x7, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  #-------------------------------------------------------------
  # Source/Destination tests
  #-------------------------------------------------------------

  test_17: li gp, 17; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x1, x1, ((7) | (-(((7) >> 11) & 1) << 11));; li x7, ((0xffffffffff000000) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;;

  #-------------------------------------------------------------
  # Bypassing tests
  #-------------------------------------------------------------

  test_18: li gp, 18; li x4, 0; 1: li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((7) | (-(((7) >> 11) & 1) << 11)); addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xffffffffff000000) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;
  test_19: li gp, 19; li x4, 0; 1: li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((14) | (-(((14) >> 11) & 1) << 11)); nop; addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xfffffffffffe0000) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;
  test_20: li gp, 20; li x4, 0; 1: li x1, ((0xffffffff80000001) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((31) | (-(((31) >> 11) & 1) << 11)); nop; nop; addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;

  test_21: li gp, 21; li x4, 0; 1: li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); srai x14, x1, ((7) | (-(((7) >> 11) & 1) << 11)); addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xffffffffff000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_22: li gp, 22; li x4, 0; 1: li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); nop; srai x14, x1, ((14) | (-(((14) >> 11) & 1) << 11)); addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xfffffffffffe0000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_23: li gp, 23; li x4, 0; 1: li x1, ((0xffffffff80000001) & ((1 << (64 - 1) << 1) - 1)); nop; nop; srai x14, x1, ((31) | (-(((31) >> 11) & 1) << 11)); addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_24: li gp, 24; srai x1, x0, ((4) | (-(((4) >> 11) & 1) << 11));; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_25: li gp, 25; li x1, ((33) & ((1 << (64 - 1) << 1) - 1)); srai x0, x1, ((10) | (-(((10) >> 11) & 1) << 11));; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne x0, x7, fail;;

  bne x0, gp, pass; fail: sll a0, gp, 1; 1:beqz a0, 1b; or a0, a0, 1; sb a0, -1(zero);; pass: li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .align 6; .global fromhost; fromhost: .dword 0; .popsection; .align 4; .global begin_signature; begin_signature:

 


