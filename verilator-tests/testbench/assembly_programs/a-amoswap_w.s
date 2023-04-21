# 0 "../../riscv-tests/isa/rv64ua/amoswap_w.S"
# 0 "<built-in>"
# 0 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ua/amoswap_w.S"
# See LICENSE for license details.

#*****************************************************************************
# amoswap_w.S
#-----------------------------------------------------------------------------

# Test amoswap.w instruction.


# 1 "/home/kaveesha/riscv-tests/env/p/riscv_test.h" 1





# 1 "/home/kaveesha/riscv-tests/env/p/../encoding.h" 1
# 7 "/home/kaveesha/riscv-tests/env/p/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ua/amoswap_w.S" 2
# 1 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h" 1






#-----------------------------------------------------------------------
# Helper macros
#-----------------------------------------------------------------------
# 20 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
# We use a macro hack to simpify code generation for various numbers
# of bubble cycles.
# 36 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# RV64UI MACROS
#-----------------------------------------------------------------------

#-----------------------------------------------------------------------
# Tests for instructions with immediate operand
#-----------------------------------------------------------------------
# 92 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Tests for an instruction with register operands
#-----------------------------------------------------------------------
# 120 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Tests for an instruction with register-register operands
#-----------------------------------------------------------------------
# 214 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test memory instructions
#-----------------------------------------------------------------------
# 347 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test jump instructions
#-----------------------------------------------------------------------
# 376 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# RV64UF MACROS
#-----------------------------------------------------------------------

#-----------------------------------------------------------------------
# Tests floating-point instructions
#-----------------------------------------------------------------------
# 735 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Pass and fail code (assumes test num is in gp)
#-----------------------------------------------------------------------
# 747 "/home/kaveesha/riscv-tests/isa/macros/scalar/test_macros.h"
#-----------------------------------------------------------------------
# Test data section
#-----------------------------------------------------------------------
# 12 "../../riscv-tests/isa/rv64ua/amoswap_w.S" 2

.macro init; .endm
.section .text.init; 

_start: j test_2

  test_2: li gp, 2; li a0, 0xffffffff80000000; li a1, 0xfffffffffffff800; la a3, amo_operand; sw a0, 0(a3); amoswap.w a4, a1, 0(a3);; li x7, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); bne a4, x7, fail;







  test_3: li gp, 3; lw a5, 0(a3); li x7, ((0xfffffffffffff800) & ((1 << (64 - 1) << 1) - 1)); bne a5, x7, fail;

  # try again after a cache miss
  test_4: li gp, 4; li a1, 0x0000000080000000; amoswap.w a4, a1, 0(a3);; li x7, ((0xfffffffffffff800) & ((1 << (64 - 1) << 1) - 1)); bne a4, x7, fail;




  test_5: li gp, 5; lw a5, 0(a3); li x7, ((0xffffffff80000000) & ((1 << (64 - 1) << 1) - 1)); bne a5, x7, fail;

  bne x0, gp, pass; fail:   1: beqz gp, 1b; sll gp, gp, 1; or gp, gp, 1; li a7, 93; addi a0, gp, 0; sb a0, -1(zero); pass:   li gp, 1; li a7, 93; li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .size tohost, 8; .align 6; .global fromhost; fromhost: .dword 0; .size fromhost, 8; .popsection; .align 4; .global begin_signature; begin_signature:

 

.align 4; .global end_signature; end_signature:

  .bss
  .align 3
amo_operand:
  .dword 0
