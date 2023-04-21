# 1 "../../riscv-tests/isa/rv64ui/subw.S"
# 1 "<built-in>"
# 1 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ui/subw.S"
# See LICENSE for license details.

#*****************************************************************************
# subw.S
#-----------------------------------------------------------------------------

# Test subw instruction.


# 1 "../../riscv-tests/env/v/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/../encoding.h" 1
# 7 "../../riscv-tests/env/v/../p/riscv_test.h" 2
# 7 "../../riscv-tests/env/v/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ui/subw.S" 2
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
# 12 "../../riscv-tests/isa/rv64ui/subw.S" 2

.macro init; .endm
.text; .global _start

_start:
	j test_2

  #-------------------------------------------------------------
  # Arithmetic tests
  #-------------------------------------------------------------

  test_2: li gp, 2; li x1, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_3: li gp, 3; li x1, ((0x0000000000000001) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000001) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_4: li gp, 4; li x1, ((0x0000000000000003) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000007) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xfffffffffffffffc) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_5: li gp, 5; li x1, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0xffffffffffff8000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x0000000000008000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_6: li gp, 6; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_7: li gp, 7; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0xffffffffffff8000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xffffffff80008000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_8: li gp, 8; li x1, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000007fff) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xffffffffffff8001) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_9: li gp, 9; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_10: li gp, 10; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000007fff) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x000000007fff8000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_11: li gp, 11; li x1, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000007fff) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x000000007fff8001) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_12: li gp, 12; li x1, ((0x000000007fffffff) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0xffffffffffff8000) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xffffffff80007fff) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_13: li gp, 13; li x1, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x0000000000000001) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_14: li gp, 14; li x1, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0x0000000000000001) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0xfffffffffffffffe) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_15: li gp, 15; li x1, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); li x2, ((0xffffffffffffffff) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2;; li x7, ((0x0000000000000000) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  #-------------------------------------------------------------
  # Source/Destination tests
  #-------------------------------------------------------------

  test_16: li gp, 16; li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x1, x1, x2;; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_17: li gp, 17; li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x2, x1, x2;; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x2, x7, fail;;
  test_18: li gp, 18; li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); subw x1, x1, x1;; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;;

  #-------------------------------------------------------------
  # Bypassing tests
  #-------------------------------------------------------------

  test_19: li gp, 19; li x4, 0; 1: li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;
  test_20: li gp, 20; li x4, 0; 1: li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; nop; addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;
  test_21: li gp, 21; li x4, 0; 1: li x1, ((15) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; nop; nop; addi x6, x14, 0; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((4) & ((1 << (64 - 1) << 1) - 1)); bne x6, x7, fail;;

  test_22: li gp, 22; li x4, 0; 1: li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_23: li gp, 23; li x4, 0; 1: li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_24: li gp, 24; li x4, 0; 1: li x1, ((15) & ((1 << (64 - 1) << 1) - 1)); li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((4) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_25: li gp, 25; li x4, 0; 1: li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); nop; li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_26: li gp, 26; li x4, 0; 1: li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); nop; li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_27: li gp, 27; li x4, 0; 1: li x1, ((15) & ((1 << (64 - 1) << 1) - 1)); nop; nop; li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((4) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_28: li gp, 28; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_29: li gp, 29; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_30: li gp, 30; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); li x1, ((15) & ((1 << (64 - 1) << 1) - 1)); nop; nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((4) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_31: li gp, 31; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; li x1, ((13) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((2) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_32: li gp, 32; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; li x1, ((14) & ((1 << (64 - 1) << 1) - 1)); nop; subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_33: li gp, 33; li x4, 0; 1: li x2, ((11) & ((1 << (64 - 1) << 1) - 1)); nop; nop; li x1, ((15) & ((1 << (64 - 1) << 1) - 1)); subw x14, x1, x2; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b; li x7, ((4) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  test_34: li gp, 34; li x1, ((-15) & ((1 << (64 - 1) << 1) - 1)); subw x2, x0, x1;; li x7, ((15) & ((1 << (64 - 1) << 1) - 1)); bne x2, x7, fail;;
  test_35: li gp, 35; li x1, ((32) & ((1 << (64 - 1) << 1) - 1)); subw x2, x1, x0;; li x7, ((32) & ((1 << (64 - 1) << 1) - 1)); bne x2, x7, fail;;
  test_36: li gp, 36; subw x1, x0, x0;; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_37: li gp, 37; li x1, ((16) & ((1 << (64 - 1) << 1) - 1)); li x2, ((30) & ((1 << (64 - 1) << 1) - 1)); subw x0, x1, x2;; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne x0, x7, fail;;

  bne x0, gp, pass; fail: sll a0, gp, 1; 1:beqz a0, 1b; or a0, a0, 1; sb a0, -1(zero);; pass: li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .align 6; .global fromhost; fromhost: .dword 0; .popsection; .align 4; .global begin_signature; begin_signature:

 


