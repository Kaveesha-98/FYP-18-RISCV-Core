#define SHOW_TERMINAL

#include "simulator.h"

int main() {
  simulator bench;

  bench.init();
  printf("bench inititated!\n");
  cout << endl;
  cout <<  flush; 
  
  while(1) {
    // cout<<std::hex<<bench.prev_pc<<endl;
    bench.step();
  }
}