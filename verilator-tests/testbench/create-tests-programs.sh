#! /bin/sh
rm -r objectfiles
rm -r elffiles
rm -r dumps
rm -r target_texts
mkdir objectfiles
for file in assembly_programs/*.s; do
  $1-as "$file" -o objectfiles/`basename "$file" ".s"`.o
done

mkdir elffiles
for file in objectfiles/*.o; do
  $1-ld -Tvirt.ld "$file" -o elffiles/`basename "$file" ".o"`
done

mkdir dumps
for file in elffiles/*; do
  $1-objdump -S "$file" > dumps/`basename "$file"`.dump
done

mkdir target_texts
for file in elffiles/*; do
  $1-objcopy -O binary "$file" target_texts/`basename "$file"`.text
done