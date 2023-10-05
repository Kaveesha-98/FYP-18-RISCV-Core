_start:
auipc t0, 0;
addi a2, t0, 40;
csrrs a0, mhartid, zero;
ld a1, 32(t0);
ld t0, 24(t0);
jr t0;
kernal_start: .dword 0x0000000080000000;
dtb_start:    .dword 0x0000000087e00000;
