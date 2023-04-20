# 1 "../../riscv-tests/isa/rv64ui/sd.S"
# 1 "<built-in>"
# 1 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ui/sd.S"
# See LICENSE for license details.

#*****************************************************************************
# sd.S
#-----------------------------------------------------------------------------

# Test sd instruction.


# 1 "../../riscv-tests/env/v/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/riscv_test.h" 1





# 1 "../../riscv-tests/env/v/../p/../encoding.h" 1
# 7 "../../riscv-tests/env/v/../p/riscv_test.h" 2
# 7 "../../riscv-tests/env/v/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ui/sd.S" 2
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
# 12 "../../riscv-tests/isa/rv64ui/sd.S" 2

.macro init; .endm
.text; .global _start

_start:
	j test_2

  #-------------------------------------------------------------
  # Basic tests
  #-------------------------------------------------------------

  test_2: li gp, 2; la x1, tdat; li x2, 0x00aa00aa00aa00aa; la x15, 7f; sd x2, 0(x1); ld x14, 0(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0x00aa00aa00aa00aa) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_3: li gp, 3; la x1, tdat; li x2, 0xaa00aa00aa00aa00; la x15, 7f; sd x2, 8(x1); ld x14, 8(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0xaa00aa00aa00aa00) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_4: li gp, 4; la x1, tdat; li x2, 0x0aa00aa00aa00aa0; la x15, 7f; sd x2, 16(x1); ld x14, 16(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0x0aa00aa00aa00aa0) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_5: li gp, 5; la x1, tdat; li x2, 0xa00aa00aa00aa00a; la x15, 7f; sd x2, 24(x1); ld x14, 24(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0xa00aa00aa00aa00a) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  # Test with negative offset

  test_6: li gp, 6; la x1, tdat8; li x2, 0x00aa00aa00aa00aa; la x15, 7f; sd x2, -24(x1); ld x14, -24(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0x00aa00aa00aa00aa) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_7: li gp, 7; la x1, tdat8; li x2, 0xaa00aa00aa00aa00; la x15, 7f; sd x2, -16(x1); ld x14, -16(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0xaa00aa00aa00aa00) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_8: li gp, 8; la x1, tdat8; li x2, 0x0aa00aa00aa00aa0; la x15, 7f; sd x2, -8(x1); ld x14, -8(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0x0aa00aa00aa00aa0) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_9: li gp, 9; la x1, tdat8; li x2, 0xa00aa00aa00aa00a; la x15, 7f; sd x2, 0(x1); ld x14, 0(x1); j 8f; 7: mv x14, x2; 8:; li x7, ((0xa00aa00aa00aa00a) & ((1 << (64 - 1) << 1) - 1)); bne x14, x7, fail;;

  # Test with a negative base

  test_10: li gp, 10; la x1, tdat9; li x2, 0x1234567812345678; addi x4, x1, -32; sd x2, 32(x4); ld x5, 0(x1);; li x7, ((0x1234567812345678) & ((1 << (64 - 1) << 1) - 1)); bne x5, x7, fail;







  # Test with unaligned base

  test_11: li gp, 11; la x1, tdat9; li x2, 0x5821309858213098; addi x1, x1, -3; sd x2, 11(x1); la x4, tdat10; ld x5, 0(x4);; li x7, ((0x5821309858213098) & ((1 << (64 - 1) << 1) - 1)); bne x5, x7, fail;
# 53 "../../riscv-tests/isa/rv64ui/sd.S"
  #-------------------------------------------------------------
  # Bypassing tests
  #-------------------------------------------------------------

  test_12: li gp, 12; li x4, 0; 1: li x1, 0xabbccdd; la x2, tdat; sd x1, 0(x2); ld x14, 0(x2); li x7, 0xabbccdd; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_13: li gp, 13; li x4, 0; 1: li x1, 0xaabbccd; la x2, tdat; nop; sd x1, 8(x2); ld x14, 8(x2); li x7, 0xaabbccd; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_14: li gp, 14; li x4, 0; 1: li x1, 0xdaabbcc; la x2, tdat; nop; nop; sd x1, 16(x2); ld x14, 16(x2); li x7, 0xdaabbcc; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_15: li gp, 15; li x4, 0; 1: li x1, 0xddaabbc; nop; la x2, tdat; sd x1, 24(x2); ld x14, 24(x2); li x7, 0xddaabbc; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_16: li gp, 16; li x4, 0; 1: li x1, 0xcddaabb; nop; la x2, tdat; nop; sd x1, 32(x2); ld x14, 32(x2); li x7, 0xcddaabb; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_17: li gp, 17; li x4, 0; 1: li x1, 0xccddaab; nop; nop; la x2, tdat; sd x1, 40(x2); ld x14, 40(x2); li x7, 0xccddaab; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;

  test_18: li gp, 18; li x4, 0; 1: la x2, tdat; li x1, 0x00112233; sd x1, 0(x2); ld x14, 0(x2); li x7, 0x00112233; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_19: li gp, 19; li x4, 0; 1: la x2, tdat; li x1, 0x30011223; nop; sd x1, 8(x2); ld x14, 8(x2); li x7, 0x30011223; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_20: li gp, 20; li x4, 0; 1: la x2, tdat; li x1, 0x33001122; nop; nop; sd x1, 16(x2); ld x14, 16(x2); li x7, 0x33001122; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_21: li gp, 21; li x4, 0; 1: la x2, tdat; nop; li x1, 0x23300112; sd x1, 24(x2); ld x14, 24(x2); li x7, 0x23300112; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_22: li gp, 22; li x4, 0; 1: la x2, tdat; nop; li x1, 0x22330011; nop; sd x1, 32(x2); ld x14, 32(x2); li x7, 0x22330011; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_23: li gp, 23; li x4, 0; 1: la x2, tdat; nop; nop; li x1, 0x12233001; sd x1, 40(x2); ld x14, 40(x2); li x7, 0x12233001; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;

  bne x0, gp, pass; fail: sll a0, gp, 1; 1:beqz a0, 1b; or a0, a0, 1; sb a0, -1(zero);; pass: li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .align 6; .global fromhost; fromhost: .dword 0; .popsection; .align 4; .global begin_signature; begin_signature:

 

tdat:
tdat1: .dword 0xdeadbeefdeadbeef
tdat2: .dword 0xdeadbeefdeadbeef
tdat3: .dword 0xdeadbeefdeadbeef
tdat4: .dword 0xdeadbeefdeadbeef
tdat5: .dword 0xdeadbeefdeadbeef
tdat6: .dword 0xdeadbeefdeadbeef
tdat7: .dword 0xdeadbeefdeadbeef
tdat8: .dword 0xdeadbeefdeadbeef
tdat9: .dword 0xdeadbeefdeadbeef
tdat10: .dword 0xdeadbeefdeadbeef


