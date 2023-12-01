.section .text
.globl start

start:
li a0, 0x04000000
get_ps_stat:
lwu a1, 0(a0)
beqz a1, get_ps_stat
li ra, 0x10000000
ret
