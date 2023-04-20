#! /bin/sh
rm -r build
mkdir build
cd build
$1-gcc -march=rv64g ../hello.c -save-temps
$1-as a-hello.s -o a-hello.o
$1-as ../init.s -o init.o
$1-ld -T../virt.ld init.o a-hello.o -o a-hello
$1-objcopy -O binary a-hello a-hello.bin
$1-objdump -D a-hello > a.hello.dump
gcc ../binary_to_text.c -o binary_to_text
./binary_to_text a-hello.bin a-hello.txt
