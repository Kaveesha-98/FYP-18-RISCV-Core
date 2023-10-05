#RISCV-TESTS = /mnt/AE06F37906F3413F/University/GitHub/riscv-tests/

CC = $(RISCV)/bin/riscv64-unknown-elf-gcc
AS = $(RISCV)/bin/riscv64-unknown-elf-as
LD = $(RISCV)/bin/riscv64-unknown-elf-ld
OBJCOPY = $(RISCV)/bin/riscv64-unknown-elf-objcopy

emulator_only.out: src/emulator_only.cpp src/emulator.h
	g++ src/emulator_only.cpp -o emulator_only.out

run_emulator: emulator_only.out
	./emulator_only.out resources/riscv_test_images/rv64ui-p-addi.bin

run_riscv_tests: emulator_only.out resources/riscv_test_images
	while IFS= read -r file; do \
		echo -n "$$file"; \
		./emulator_only.out resources/riscv_test_images/"$$file".bin; \
	done < resources/supported-tests.txt

resources/riscv_test_images: resources/supported-tests.txt
	@echo "Creating test images from $(RISCV-TESTS)"
	mkdir resources/riscv_test_images || { rm -r resources/riscv_test_images; mkdir resources/riscv_test_images; }
	while IFS= read -r file; do \
		$(RISCV)/bin/riscv64-unknown-elf-objcopy -O binary $(RISCV-TESTS)/isa/"$$file" resources/riscv_test_images/"$$file".bin || { rm -r resources/riscv_test_images; exit 1; }; \
	done < resources/supported-tests.txt

resources/build:
	mkdir resources/build

resources/build/qemu_rom: resources/qemu_boot_rom/boot.s resources/qemu_boot_rom/virt.ld resources/build
	cd resources/qemu_boot_rom; \
	$(AS) boot.s -o boot.o; \
	$(LD) -Tvirt.ld boot.o -o boot.out; \
	$(OBJCOPY) -O binary boot.out boot.bin; \
	cd ../build; \
	rm -r qemu_rom; mkdir qemu_rom; cd qemu_rom; \
	cp ../../qemu_boot_rom/boot.bin .

qemu_mimic.out: src/qemu_mimic.cpp src/emulator.h
	g++ src/qemu_mimic.cpp -O3 -o qemu_mimic.out

boot_linux: qemu_mimic.out resources/Image resources/qemu.dtb resources/build/qemu_rom
	./qemu_mimic.out resources/Image


clean:
	rm emulator_only.out

#.PHONY: emulator_only