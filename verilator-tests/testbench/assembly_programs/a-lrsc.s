# 0 "../../riscv-tests/isa/rv64ua/lrsc.S"
# 0 "<built-in>"
# 0 "<command-line>"
# 1 "../../riscv-tests/isa/rv64ua/lrsc.S"
# See LICENSE for license details.

#*****************************************************************************
# lrsr.S
#-----------------------------------------------------------------------------

# Test LR/SC instructions.


# 1 "/home/kaveesha/riscv-tests/env/p/riscv_test.h" 1





# 1 "/home/kaveesha/riscv-tests/env/p/../encoding.h" 1
# 7 "/home/kaveesha/riscv-tests/env/p/riscv_test.h" 2
# 11 "../../riscv-tests/isa/rv64ua/lrsc.S" 2
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
# 12 "../../riscv-tests/isa/rv64ua/lrsc.S" 2

.macro init; .endm
.section .text.init; 



 # get a unique core id
_start: la a0, coreid
li a1, 1
amoadd.w a2, a1, (a0)

# for now, only run this on core 0
1:li a3, 1
bgeu a2, a3, 1b

1: lw a1, (a0)
bltu a1, a3, 1b

# make sure that sc without a reservation fails.
test_2: li gp, 2; la a0, foo; li a5, 0xdeadbeef; sc.w a4, a5, (a0);; li x7, ((1) & ((1 << (64 - 1) << 1) - 1)); bne a4, x7, fail;





 # make sure the failing sc did not commit into memory
test_3: li gp, 3; lw a4, foo;; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne a4, x7, fail;




 # Disable test case 4 for now. It assumes a <1K reservation granule, when
# in reality any size granule is valid. After discussion in issue #315,
# decided to simply disable the test for now.
# (See https:

## make sure that sc with the wrong reservation fails.
## TODO is this actually mandatory behavior?
#test_4: li gp, 4; # la a0, foo; # la a1, fooTest3; # lr.w a1, (a1); # sc.w a4, a1, (a0); #; li x7, ((1) & ((1 << (64 - 1) << 1) - 1)); bne a4, x7, fail;
# 57 "../../riscv-tests/isa/rv64ua/lrsc.S"
 # have each core add its coreid+1 to foo 1024 times
la a0, foo
li a1, 1<<5
addi a2, a2, 1
1: lr.w a4, (a0)
add a4, a4, a2
sc.w a4, a4, (a0)
bnez a4, 1b
add a1, a1, -1
bnez a1, 1b

# wait for all cores to finish
la a0, barrier
li a1, 1
amoadd.w x0, a1, (a0)
1: lw a1, (a0)
blt a1, a3, 1b

# expected result is 512*ncores*(ncores+1)
test_5: li gp, 5; lw a0, foo; slli a1, a3, 5 -1; 1:sub a0, a0, a1; addi a3, a3, -1; bgez a3, 1b; li x7, ((0) & ((1 << (64 - 1) << 1) - 1)); bne a0, x7, fail;







 # make sure that sc-after-successful-sc fails.
test_6: li gp, 6; la a0, foo; 1:lr.w a1, (a0); sc.w a1, x0, (a0); bnez a1, 1b; sc.w a1, x0, (a0); li x7, ((1) & ((1 << (64 - 1) << 1) - 1)); bne a1, x7, fail;







bne x0, gp, pass; fail: 1: beqz gp, 1b; sll gp, gp, 1; or gp, gp, 1; li a7, 93; addi a0, gp, 0; sb a0, -1(zero); pass: li gp, 1; li a7, 93; li a0, 1; sb a0, -1(zero)

unimp

  .data
 .pushsection .tohost,"aw",@progbits; .align 6; .global tohost; tohost: .dword 0; .size tohost, 8; .align 6; .global fromhost; fromhost: .dword 0; .size fromhost, 8; .popsection; .align 4; .global begin_signature; begin_signature:

 

coreid: .word 0
barrier: .word 0
foo: .word 0
.skip 1024
fooTest3: .word 0
.align 4; .global end_signature; end_signature:
