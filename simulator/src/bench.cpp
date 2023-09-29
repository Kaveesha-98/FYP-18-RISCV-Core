#include "simulator.h"

int main() {
  simulator bench;

  bench.init();
  printf("bench inititated!\n");
  
  while(1) {
    bench.step();
    cout<<std::dec<<bench.prev_pc<<endl;
  }
}