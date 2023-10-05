#include <iostream>
#include <fstream>
#include <string>
#include "emulator.h"

emulator golden_model;

int main(int argc, char* argv[]) {
  // Name of kernel image must be provided at run time
  if (argc == 1) {
    printf("Name of kerenl image must be provided at run time\n");
    return 1;
  } else if (argc > 2) {
    printf("Too many arguments provided\n");
  }

  if (!golden_model.load_kernel(argv[1])) {
    printf("kernel loading failed\n");
    return 1;
  }
  golden_model.load_kernel("resources/qemu.dtb", 0x7e00000UL);
  golden_model.load_bootrom("resources/build/qemu_rom/boot.bin");
  char x;
  golden_model.init();
  /* golden_model.step();
  golden_model.step(); */
  while(1){//for (int i = 0; i < 50000; i++) {
    //golden_model.show_state();
    //cin >> x;
    golden_model.step();
    switch (golden_model.check_test_status()) {
    case 0:
      /* code */
      break;

    case 1:
      printf(" Test passed!\n");
      return 0;
    
    default:
      printf(" Test failed at test %d!\n", golden_model.check_test_status() >> 1);
      return 1;
    }
  }
  printf(" Test failed: Time-out!\n");

  return 1;
}