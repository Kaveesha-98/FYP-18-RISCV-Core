# 1 "../../riscv-tests/isa/rv64ui/bltu.S"
# 1 "<built-in>"
# 1 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ui/bltu.S"
# See LICENSE for license details.

#*****************************************************************************
# bltu.S
#-----------------------------------------------------------------------------

# Test bltu instruction.


# 1 "../../riscv-tests/env/v/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/../encoding.h" 1
# 7 "../../riscv-tests/env/v/../p/riscv_test.h" 2
# 7 "../../riscv-tests/env/v/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ui/bltu.S" 2
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
# 12 "../../riscv-tests/isa/rv64ui/bltu.S" 2

.macro init; .endm
.text; .global _start

_start:
	j test_2

  #-------------------------------------------------------------
  # Branch tests
  #-------------------------------------------------------------

  # Each test checks both forward and backward branches

  test_2: li gp, 2; li x1, 0x00000000; li x2, 0x00000001; bltu x1, x2, 2f; bne x0, gp, fail; 1: bne x0, gp, 3f; 2: bltu x1, x2, 1b; bne x0, gp, fail; 3:;
  test_3: li gp, 3; li x1, 0xfffffffe; li x2, 0xffffffff; bltu x1, x2, 2f; bne x0, gp, fail; 1: bne x0, gp, 3f; 2: bltu x1, x2, 1b; bne x0, gp, fail; 3:;
  test_4: li gp, 4; li x1, 0x00000000; li x2, 0xffffffff; bltu x1, x2, 2f; bne x0, gp, fail; 1: bne x0, gp, 3f; 2: bltu x1, x2, 1b; bne x0, gp, fail; 3:;

  test_5: li gp, 5; li x1, 0x00000001; li x2, 0x00000000; bltu x1, x2, 1f; bne x0, gp, 2f; 1: bne x0, gp, fail; 2: bltu x1, x2, 1b; 3:;
  test_6: li gp, 6; li x1, 0xffffffff; li x2, 0xfffffffe; bltu x1, x2, 1f; bne x0, gp, 2f; 1: bne x0, gp, fail; 2: bltu x1, x2, 1b; 3:;
  test_7: li gp, 7; li x1, 0xffffffff; li x2, 0x00000000; bltu x1, x2, 1f; bne x0, gp, 2f; 1: bne x0, gp, fail; 2: bltu x1, x2, 1b; 3:;
  test_8: li gp, 8; li x1, 0x80000000; li x2, 0x7fffffff; bltu x1, x2, 1f; bne x0, gp, 2f; 1: bne x0, gp, fail; 2: bltu x1, x2, 1b; 3:;

  #-------------------------------------------------------------
  # Bypassing tests
  #-------------------------------------------------------------

  test_9: li gp, 9; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_10: li gp, 10; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_11: li gp, 11; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; nop; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_12: li gp, 12; li x4, 0; 1: li x1, 0xf0000000; nop; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_13: li gp, 13; li x4, 0; 1: li x1, 0xf0000000; nop; li x2, 0xefffffff; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_14: li gp, 14; li x4, 0; 1: li x1, 0xf0000000; nop; nop; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;

  test_15: li gp, 15; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_16: li gp, 16; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_17: li gp, 17; li x4, 0; 1: li x1, 0xf0000000; li x2, 0xefffffff; nop; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_18: li gp, 18; li x4, 0; 1: li x1, 0xf0000000; nop; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_19: li gp, 19; li x4, 0; 1: li x1, 0xf0000000; nop; li x2, 0xefffffff; nop; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_20: li gp, 20; li x4, 0; 1: li x1, 0xf0000000; nop; nop; li x2, 0xefffffff; bltu x1, x2, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;

  #-------------------------------------------------------------
  # Test delay slot instructions not executed nor bypassed
  #-------------------------------------------------------------

  test_21: li gp, 21; li x1, 1; bltu x0, x1, 1f; addi x1, x1, 1; addi x1, x1, 1; addi x1, x1, 1; addi x1, x1, 1; 1: addi x1, x1, 1; addi x1, x1, 1;; li x7, ((3) & ((1 << (64 - 1) << 1) - 1)); bne x1, x7, fail;
# 64 "../../riscv-tests/isa/rv64ui/bltu.S"
  bne x0, gp, pass; fail: sll a0, gp, 1; 1:beqz a0, 1b; or a0, a0, 1; sb a0, -1(zero);; pass: li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .align 6; .global fromhost; fromhost: .dword 0; .popsection; .align 4; .global begin_signature; begin_signature:

 


