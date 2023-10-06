#include <iostream>
#include <fstream>
#include <string>
#include "emulator.h"
#include <chrono>
#include <unistd.h>
#include <cstdlib>
#include <signal.h>
#include <sys/ioctl.h>
#include <termios.h>
using namespace std;

using namespace std::chrono;

#define LOGGING

emulator golden_model;

struct keystroke_buffer {
  unsigned char reader, writer, char_buffer[128];
};

void enable_raw_mode() {
  termios term;
  tcgetattr(0, &term);
  term.c_lflag &= ~(ICANON | ECHO); // Disable echo as well
  tcsetattr(0, TCSANOW, &term);
}

void disable_raw_mode() {
  termios term;
  tcgetattr(0, &term);
  term.c_lflag |= ICANON | ECHO;
  tcsetattr(0, TCSANOW, &term);
}

// Define the function to be called when ctrl-c (SIGINT) is sent to process
void signal_callback_handler(int signum) {
  golden_model.show_state();
  disable_raw_mode();
  tcflush(0, TCIFLUSH); 
  // Terminate program
  exit(signum);
}

int kbhit()
{
  int byteswaiting;
  ioctl(0, FIONREAD, &byteswaiting);
  return byteswaiting > 0;
}

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
  golden_model.load_dtb("resources/qemu.dtb", 0x7e00000UL);
  golden_model.load_bootrom("resources/build/qemu_rom/boot.bin");
  golden_model.load_symbols("resources/symbol_names.txt", "resources/symbol_pointers.bin");
  char x;
  golden_model.init();
  //golden_model.print_symbols();
  /* golden_model.step();
  golden_model.step(); */
  #ifdef LOGGING
  std::ofstream outFile("run.log"); // This will create or overwrite the file

  // Check if the file is open
  if (!outFile.is_open()) {
    std::cerr << "Error opening the file." << std::endl;
    return 1;
  }
  #endif
  std::ifstream inputFile("resources/symbol_names.txt");

  if (!inputFile.is_open()) {
    std::cerr << "Failed to open the file." << std::endl;
    return 1;
  }

  std::vector<std::string> symbols;

  std::string line;
  while (std::getline(inputFile, line)) {
    symbols.push_back(line);
  }

  inputFile.close();

  // Now you can access and print the stored symbols
  /* for (const std::string& storedLine : symbols) {
    std::cout << storedLine << std::endl;
  } */
  unsigned long old_symbol = 1;
  unsigned long mem_address, data;
  unsigned long delta = 10000000;
  //for (int i; i < 10; i++) {
  printf("stepping\n");
  // Use auto keyword to avoid typing long
  // type definitions to get the timepoint
  // at this instant use function now()
  auto start = high_resolution_clock::now();
  int timer_interr = 0;
  signal(SIGINT, signal_callback_handler);

  enable_raw_mode();
  
  keystroke_buffer keys_rx;
  keys_rx.reader = 0;
  keys_rx.writer = 0;

  while (1) {
    // golden_model.show_state();
    //cin >> x;
    if (kbhit()) {
      // printf("detected input, %c\n", getchar());
      keys_rx.char_buffer[keys_rx.writer++] = getchar();
      keys_rx.reader += (keys_rx.reader == keys_rx.writer); // overflow
      outFile << "keyhit\n";
    }
    
    if (keys_rx.reader != keys_rx.writer) { keys_rx.reader += golden_model.load_rx_char(keys_rx.char_buffer[keys_rx.reader]); }

    #ifdef LOGGING
    if (golden_model.get_instruction() == 0x00100073) 
      break;

    //unsigned long current_symbol = golden_model.get_symbom_index(golden_model.get_pc(), old_symbol);

    /* if (current_symbol != old_symbol)
    {
      outFile << setfill('0') << setw(8) << hex << golden_model.get_pc() << " " << symbols[current_symbol];// << "\n";
      outFile << setfill('0') << setw(8) << hex << golden_model.get_csr_value(MIE) << "\n";
      old_symbol = current_symbol;
    } */
    outFile <<  setfill('0') << setw(16) << hex << golden_model.get_pc() << "\n";
    /* switch (golden_model.check_for_mem_access(&mem_address, &data))
    {
    case 1:
      //if (mem_address == 0x80001000) { printf("0x%016lx\n", data); }
      if (mem_address >= 0x80000000 && mem_address < 0x90000000) { break; }
      outFile << "load access at " << setfill('0') << setw(16) << hex << mem_address;
      outFile << " reading data " << setfill('0') << setw(16) << hex << data << "\n";
      break;
    
    case 2:
      if (mem_address >= 0x80000000 && mem_address < 0x90000000) { break; }
      outFile << "store access at " << setfill('0') << setw(16) << hex << mem_address;
      outFile << " writing data " << setfill('0') << setw(16) << hex << data << "\n";
      break;

    case 3:
      if (mem_address >= 0x80000000 && mem_address < 0x90000000) { break; }
      outFile << "atomic access at " << setfill('0') << setw(16) << hex << mem_address;
      outFile << " reading data " << setfill('0') << setw(16) << hex << data << "\n";
      break;
        
    default:
      break;
    } */
    #endif
    /* if (
      golden_model.check_for_mem_access(&mem_address, &data) && 
      (mem_address == 0x10000004) &&
      (data == 0))
    {
      break;
    } */
    // golden_model.show_state();
    auto stop = high_resolution_clock::now();
    auto duration = duration_cast<microseconds>(stop - start);
    /* if (timer_interr == 0)
      if (duration.count() > 10000000) { printf("Timer might be working"); timer_interr++; } */
    if (duration.count() > 10000000) {
      //printf("Timer might be working"); 
      //start = high_resolution_clock::now();
      //golden_model.set_mtip();
    }
    golden_model.set_mtime(duration.count()*10);
    //printf("%d\n", timer_interr);
    // Write data to the file
    //printf("Timer might be working");
    
    golden_model.step();
    
  }
  #ifdef LOGGING
  outFile.close();
  #endif

  printf(" Test failed: Time-out!\n");
  disable_raw_mode();
  tcflush(0, TCIFLUSH); 

  return 1;
}