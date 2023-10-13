#include <iostream>
#include <fstream>
#include <string>
#include <iomanip>
#include <ctime>
#include <cmath>
#include <vector>
#include <chrono>
using namespace std::chrono;

#define SHOW_TERMINAL

//#define DEBUG

#define RAM_SIZE 536870912
#define PROGRAM_INIT 0x87ffff00UL // starting pc of the first instruction run
#define RAM_BASE 0x80000000UL
#define RAM_HIGH 0x90000000UL
#define BOOTROM_SIZE 1048576
#define BOOTROM_BASE 0x1000UL

#define MVENDORID  0xf11
#define MARCHID    0xf12
#define MIMPID     0xf13
#define MHARTID    0xf14
#define MCONFIGPTR 0xf15
#define MSTATUS    0x300
#define MISA       0x301 // implemented RV64I
#define MEDELEG    0x302
#define MIDELEG    0x303
#define MIE        0x304
#define MTVEC      0x305
#define MCOUNTEREN 0x306
#define MSCRATCH   0x340
#define MEPC       0x341
#define MCAUSE     0x342
#define MTVAL      0x343
#define MIP        0x344
#define MTINST     0x34a
#define MTVAL2     0x34b
#define MENVCFG    0x30a
#define MSECCFG    0x747
#define PMPCFG0    0x3a0
#define MCYCLE     0xb00
#define MINSTRET   0xb02
#define MHPMCOUNTER3 0xB03
#define MCOUNTINHIBIT 0x320
#define MHPMEVENT3 0x323
#define TSELECT    0x7a0
#define TDATA1     0x7a1
#define TDATA2     0x7a2
#define TDATA3     0x7a3
#define MCONTEXT   0x7a8

using namespace std;

/**
 * struct for defining a reservation set for atmoic accesses
 * 
 * @param address address reserved
 * @param valid whether there is a valid set (1 - valid, 0 - invalid)
 * @param size how many bytes reserved (2 - 4 bytes, 3 - 8 bytes)
*/
struct atomic_reservation {
  unsigned long address;
  unsigned long data;
  unsigned char valid;
  unsigned char size;
};


class emulator {
private:
  // pointer of the instruction to be executed
  unsigned long pc;
  // main memory of the system
  unsigned char memory[RAM_SIZE];
  // boot rom that has the boot kernel
  unsigned char boot_rom[BOOTROM_SIZE];
  // constrol and status registers of the hart
  unsigned long csrs[4096];
  // current priviledge of the harts
  unsigned char priviledge;
  // a temp variable to store temporary data
  unsigned char temp;
  // for implmenting atomics
  atomic_reservation reservation = {0UL, 0UL, 0, 0};
  // symbol names for debugging
  std::vector<std::string> symbols;
  // matching pointer of debugging symbol
  std::vector<unsigned long> symbol_pointers;
  unsigned long current_symbol_index, last_symbol_index;
  unsigned char dtb[RAM_SIZE];
  std::ofstream outputFile;
  clock_t time_base;
  unsigned long mtime, mtimecmp;
  unsigned char rx_ready, rx_char;
  
public:
  // general purpose resgisters of the hart
  unsigned long gprs[32];
  int load_rx_char(unsigned char rx_byte) {
    if (rx_ready)
      return 0; 

    rx_char = rx_byte; 
    rx_ready = 1; 
    return 1;
  }

  void set_mtime(unsigned long current_time) { mtime = current_time; }

  /**
   * programs the memory with the raw binary file of the program to
   * be executed.
   * 
   * @param image_name file name of the raw binary file
   * @param offset load binary file with an optional offset in memory
  */
  int load_kernel(std::string image_name, unsigned int offset = 0) {
    std::ifstream inputFile(image_name, std::ios::binary);

    if (inputFile.is_open()) {
      // Get the file size
      inputFile.seekg(0, std::ios::end);
      std::streampos fileSize = inputFile.tellg();
      inputFile.seekg(0, std::ios::beg);

      // Make sure image size does not exceed allocate memory size
      if(static_cast<std::streamoff>(fileSize) > RAM_SIZE) {
        std::cout << "File size of kernel image too large" << std::endl;
        inputFile.close();
        return 0;
      }
      // Read the file into the array
      inputFile.read(reinterpret_cast<char*>(&memory[offset/8]), fileSize);

      inputFile.close(); // Close the file when done
    } else {
      std::cout << "Failed to open the file." << std::endl;
      return 0;
    }

    return 1;
  }

  /**
   * programs the memory with the raw binary file of the program to
   * be executed.
   * 
   * @param image_name file name of the raw binary file
   * @param offset load binary file with an optional offset in memory
  */
  int load_dtb(std::string image_name, unsigned int offset = 0) {
    std::ifstream inputFile(image_name, std::ios::binary);

    if (inputFile.is_open()) {
      // Get the file size
      inputFile.seekg(0, std::ios::end);
      std::streampos fileSize = inputFile.tellg();
      inputFile.seekg(0, std::ios::beg);

      // Make sure image size does not exceed allocate memory size
      if(static_cast<std::streamoff>(fileSize) > RAM_SIZE) {
        std::cout << "File size of kernel image too large" << std::endl;
        inputFile.close();
        return 0;
      }
      // Read the file into the array
      inputFile.read(reinterpret_cast<char*>(&dtb), fileSize);

      inputFile.close(); // Close the file when done
    } else {
      std::cout << "Failed to open the file." << std::endl;
      return 0;
    }

    return 1;
  }

  /**
   * programs the memory with the raw binary file of the boot rom image to
   * be executed.
   * 
   * @param image_name file name of the raw binary file
   * @param offset load binary file with an optional offset in memory
  */
  int load_bootrom(std::string image_name, unsigned int offset = 0) {
    std::ifstream inputFile(image_name, std::ios::binary);

    if (inputFile.is_open()) {
      // Get the file size
      inputFile.seekg(0, std::ios::end);
      std::streampos fileSize = inputFile.tellg();
      inputFile.seekg(0, std::ios::beg);

      // Make sure image size does not exceed allocate memory size
      if(static_cast<std::streamoff>(fileSize) > BOOTROM_SIZE) {
        std::cout << "File size of boot rom image too large" << std::endl;
        inputFile.close();
        return 0;
      }
      // Read the file into the array
      inputFile.read(reinterpret_cast<char*>(boot_rom), fileSize);

      inputFile.close(); // Close the file when done
    } else {
      std::cout << "Failed to open the file." << std::endl;
      return 0;
    }

    return 1;
  }

  /**
   * Initializes system registers to a starting point
  */
  void init() {
    pc = PROGRAM_INIT;
    //csrs[MINSTRET] = 0;
    priviledge = 3;
    csrs[MSTATUS] = 0xa00000000;
    current_symbol_index = 0;
    //std::ofstream outputFile("emulator.log");
    last_symbol_index = symbol_pointers.size() - 1;
    for (unsigned long i = 0x07e00000; i < (0x90000000 - 0x87e00000); i++)
    {
      memory[i] = dtb[i-0x07e00000];
    }
    rx_ready = 0;

    for (unsigned long i = 0x07ffff00; i < (0x90000000 - 0x87ffff00); i++) {
      memory[i] = boot_rom[i - 0x07ffff00];
    }
    //printf("%ld, %ld\n", symbol_pointers.size(), symbols.size());
    
  }

  /**
   * Performs instruction fetching
  */
  unsigned int fetch_instruction(unsigned long address) {
    if ((address <= (BOOTROM_BASE + BOOTROM_SIZE)) && (address >= BOOTROM_BASE)) {
      return *reinterpret_cast<unsigned int*>(&boot_rom[address - BOOTROM_BASE]);
    } else if ((address >= RAM_BASE) && (address <= RAM_HIGH)) {
      return *reinterpret_cast<unsigned int*>(&memory[address - RAM_BASE]);
    } else {
      printf("Instruction access out of range: %lx\n", address);
      cin >> temp;
    }
    return 0U;
  }

  /**
   * Performs data fetching
  */
  unsigned long fetch_long(unsigned long address) {
    #ifndef SHOW_TERMINAL
      // printf("Data access at %lx\n", address);
      //cout << "Data access at " << hex << address << endl;
    #endif
    if ((address <= (BOOTROM_BASE + BOOTROM_SIZE)) && (address >= BOOTROM_BASE)) {
      return *reinterpret_cast<unsigned long*>(&boot_rom[address - BOOTROM_BASE]);
    } else if ((address >= RAM_BASE) && (address <= RAM_HIGH)) {
      

      return *reinterpret_cast<unsigned long*>(&memory[address - RAM_BASE]);
    } else {
      /* printf("Data access out of range: %lx\n", address);
      cin >> temp; */
      #ifndef SHOW_TERMINAL
        printf("Data access at %lx\n", address);
        cout << "Data access at " << hex << address << endl;
      #endif
      if (address == 0xe000002cUL)
      {
        return 8;
      }
      if (address == 0x10000005)
      { 
        rx_ready = 0;
        return 96UL + rx_ready;
      }
      if (address == 0x10000003)
      {
        return 3UL; 
      }
      if (address == 0x200bff8)
      {

        return 0; //mtime; 
      }
      if (address == 0xc200004) {
        return (rx_ready ? 1 : 0);
      }
      if (((address & (~0xffffUL)) == 0x10000000UL) && (((address >> 12)&0xf) < 9) && ((address&(~0xfffUL)) != 0x10000000)) { 
        switch (address&0xfffUL)
        {
        case 0x000:
          return 0x74726976UL; 

        case 0x004:
          return 2UL;

        case 0x008:
          return 3UL;

        case 0x070:
          return ~0UL;
        
        default:
          return 0UL;
        }
      } 
      
    }
    return 0;
  }

  void print_symbols() {
    std::string symbol_name;
    unsigned long index = 0;
    for (const std::string& symbol_name : symbols) { 
      std::cout << symbol_name;
      printf(" %lx\n", symbol_pointers[index++]);  
    }
  }

  unsigned long get_symbom_index(unsigned long pc, unsigned long old_index = 0UL) {
    //printf ("%d, %d, %d, %d\n", pc, old_index, last_symbol_index, symbol_pointers[old_index]);
    
    if ((pc >= symbol_pointers[old_index]) && ((old_index == last_symbol_index) || (pc < symbol_pointers[old_index +1]))) { return old_index; }


    unsigned long new_symbol_index = 0;
    for (const unsigned long address : symbol_pointers) {
      if (address > pc)
        return --new_symbol_index;

      new_symbol_index++;
    }
    return --new_symbol_index;
  }

  unsigned long get_pc() { return pc; }
  unsigned int get_instruction() { return fetch_instruction(pc); }

  int check_for_mem_access(unsigned long *address, unsigned long *data) {
    unsigned int instruction = fetch_instruction(pc);
    switch ((instruction >> 2) & 31)
    {
    case 0b00000:
      *address = gprs[(instruction >> 15)&31] + ((unsigned long) (((signed long) instruction) >> 20));
      *data = fetch_long(*address);
      return 1;

    case 0b01000:
      *address = gprs[(instruction >> 15)&31] + (((unsigned long) (((signed long) instruction) >> 20)) & (~31)) + ((instruction >> 7)&31);
      *data = gprs[(instruction >> 20) & 31];
      return 2;

    case 0b01011:
      *address = gprs[(instruction >> 15)&31];
      *data = fetch_long(*address);
      return 3;
    
    default:
      return 0;
    }
  }

  unsigned long get_csr_value(int address) { return csrs[address]; }

  void write_to_memory(long unsigned address, long unsigned rs2, unsigned char funct3) {
    #ifdef DEBUG
      printf("store\n");
    #endif
    /* if (rs1+immediate_s_type == 0x80001000UL) {
      printf("result of test %ld\n", gprs[3]);
      pc = pc + 4;
      break;
    } */
    #ifndef SHOW_TERMINAL
      // printf("Data write access for %lx with data %lx\n", (rs1+immediate_s_type), rs2);
    #endif
    if (((address) <= (BOOTROM_BASE + BOOTROM_SIZE)) && ((address) >= BOOTROM_BASE)) {
      for (int i = 0; i < (1 << funct3); i++)
        memory[address+i-BOOTROM_BASE] = (unsigned char) (rs2 >> (8*i));
    } else if (((address) >= RAM_BASE) && ((address) <= RAM_HIGH)) {
      for (int i = 0; i < (1 << funct3); i++)
        memory[address+i-RAM_BASE] = (unsigned char) (rs2 >> (8*i));
      
    } else {
      /* printf("Data access out of range for writes: %lx\n", (address));
      cin >> temp; */
      /* #ifndef SHOW_TERMINAL
        printf("Data write access for %lx with data %lx\n", (address), rs2);
      #endif */
      #ifdef SHOW_TERMINAL
        if (address == 0xe0000030UL){
          printf("%c", (char) rs2&0xff);
          cout << flush;
        }
      #endif
      if (address = 0x2004000) { mtimecmp = rs2; }
    }
    /* for (int i = 0; i < (1 << funct3); i++)
      memory[address+i-RAM_BASE] = (unsigned char) (rs2 >> (8*i)); */
    
  }

  void set_mtip() { csrs[MIP] = csrs[MIP] | (1UL << 7);}

  /**
   * Executes the instruction pointed by the pc
  */
  int step() {
    csrs[MCYCLE]++;
    gprs[0] = 0;
    //printf("stepping %d\n", get_symbom_index(pc));
    
    unsigned int instruction = fetch_instruction(pc);
    if (
      ((csrs[MSTATUS]&8) || (priviledge!=3)) && 
      ((csrs[MIE]) & (1UL << 3)) &&
      (rx_ready)
    ) { 
      csrs[MEPC] = pc;
      csrs[MCAUSE] = 11 | (1UL << 63);
      csrs[MTVAL] = 0;
      pc = csrs[MTVEC];
      // setting MPP
      csrs[MSTATUS] = csrs[MSTATUS] & (~(3UL << 11));
      csrs[MSTATUS] = csrs[MSTATUS] | (((unsigned long) priviledge) << 11);
      // setting MPIE
      csrs[MSTATUS] = csrs[MSTATUS] & (~(0b10000000UL));
      csrs[MSTATUS] = csrs[MSTATUS] | ((csrs[MSTATUS] << 4) & (0b10000000UL));
      // setting MIE
      csrs[MSTATUS] = csrs[MSTATUS] & (~(0b1000UL));
      priviledge = 3;
      csrs[MIP] = csrs[MIP] & (~(1UL << 7));
      // rx_ready = 0;
      printf("External interrupt taken\n");
      return 0;
    }
    if (
      ((csrs[MSTATUS]&8) || (priviledge!=3)) && 
      ((csrs[MIE]) & (1UL << 7)) &&
      (mtime > mtimecmp)
    ) { 
      csrs[MEPC] = pc;
      csrs[MCAUSE] = 7 | (1UL << 63);
      csrs[MTVAL] = 0;
      pc = csrs[MTVEC];
      // setting MPP
      csrs[MSTATUS] = csrs[MSTATUS] & (~(3UL << 11));
      csrs[MSTATUS] = csrs[MSTATUS] | (((unsigned long) priviledge) << 11);
      // setting MPIE
      csrs[MSTATUS] = csrs[MSTATUS] & (~(0b10000000UL));
      csrs[MSTATUS] = csrs[MSTATUS] | ((csrs[MSTATUS] << 4) & (0b10000000UL));
      // setting MIE
      csrs[MSTATUS] = csrs[MSTATUS] & (~(0b1000UL));
      priviledge = 3;
      csrs[MIP] = csrs[MIP] & (~(1UL << 7));
      //printf("Timer interrupt taken\n");
      return 0;
    }
    // if (pc == 0x8002b440UL) { pc = 0x80043a6cUL; }
    
    unsigned long rs1 = gprs[(instruction >> 15) & 31];
    unsigned long rs2 = gprs[(instruction >> 20) & 31];
    unsigned int funct3 = (instruction >> 12) & 7; 

    #ifndef SHOW_TERMINAL
      if (current_symbol_index != get_symbom_index(pc)) {
        current_symbol_index = get_symbom_index(pc);
        // printf("tp: %016lx instruction: %08lx ", gprs[4], instruction);
        cout << symbols[current_symbol_index] << "  " << hex << pc << "                                                         \r";
      }
      if (instruction == 0x00100073){ 
        return 0;
      }
    #endif

    // stores immediate results of execution
    unsigned long results[8];

    // generates immediates
    unsigned long immediate_i_type = (0UL-((instruction >> 20) & 0x00000800)) | ((instruction >> 20) & 0x00000FFF);
    unsigned long immediate_b_type = (0UL-((instruction >> 19) & 0x00001000)) | ((instruction >> 19) & 0x00001000) | ((instruction >> 20) & 0x000007E0) | ((instruction << 4) & 0x00000800) | ((instruction >> 7) & 0x0000001E);
    unsigned long immediate_j_type = (0UL-((instruction >> 11) & 0x00100000)) | ((instruction >> 11) & 0x00100000) | ((instruction >> 20) & 0x000007FE) | ((instruction >> 9) & 0x00000800) | (instruction & 0x000FF000);
    unsigned long immediate_s_type = (0UL-((instruction >> 20) & 0x00000800)) | ((instruction >> 20) & 0x00000FE0) | ((instruction >> 7) & 0x0000001F);
    unsigned long immediate_u_type = (0UL-(instruction & 0x80000000)) | (instruction & (0xFFFFF000));

    switch ((instruction >> 2) & 31) {
    case 0b01100: // register-register arithmetic 64 bit
      #ifdef DEBUG
        printf("op\n");
      #endif
      if (instruction & (1 << 25)) {
        results[0] = ((signed long) rs1) * ((signed long) rs2);
        results[1] = mulh(rs1, rs2);
        results[2] = mulhsu(rs1, rs2);
        results[3] = mulhu(rs1, rs2);
        results[4] = div(rs1, rs2);
        results[5] = (rs2 == 0UL) ? ~0UL : rs1/rs2;
        results[6] = rem(rs1, rs2);
        results[7] = (rs2 == 0UL) ? rs1 : rs1%rs2;
      } else {
        results[0] = (instruction >> 30) ? (rs1 - rs2) : (rs1 + rs2);
        results[1] = (rs1 << (rs2 & 63));
        results[2] = (( (signed long) rs1) < ( (signed long) rs2));
        results[3] = (rs1 < rs2);
        results[4] = (rs1 ^ rs2);
        results[5] = (instruction >> 30) ? (((signed long) rs1) >> (rs2 & 63)) : (rs1 >> (rs2 & 63));
        results[6] = rs1 | rs2;
        results[7] = rs1 & rs2;
      }
      gprs[(instruction >> 7) & 31] = results[funct3];
      pc = pc + 4;
      break;
    
    case 0b00100: // register-immediate arithmetic 64 bit
      #ifdef DEBUG
        printf("op-imm\n");
      #endif
      results[0] = (rs1 + immediate_i_type);
      results[1] = (rs1 << (immediate_i_type & 63));
      results[2] = (( (signed long) rs1) < ( (signed long) immediate_i_type));
      results[3] = (rs1 < immediate_i_type);
      results[4] = (rs1 ^ immediate_i_type);
      results[5] = (instruction & (1 << 30)) ? (((signed long) rs1) >> (immediate_i_type & 63)) : (rs1 >> (immediate_i_type & 63));
      results[6] = rs1 | immediate_i_type;
      results[7] = rs1 & immediate_i_type;
      gprs[(instruction >> 7) & 31] = results[funct3];
      pc = pc + 4;
      break;

    case 0b01000: // memory writes integer
      write_to_memory(rs1 + immediate_s_type, rs2, (instruction >> 12) & 7);
      pc = pc + 4;
      break;

    case 0b00000: // memory reads integer
      #ifdef DEBUG
        printf("load\n");
      #endif
      results[3] = fetch_long(rs1+immediate_i_type);
      if ((rs1+immediate_i_type) >= 0x87e00000) {
        // printf("DTB Access %lx at address %lx\n", results[3], (rs1+immediate_i_type));
        //return *reinterpret_cast<unsigned long*>(&dtb[address - 0x87e00000]);  
      }

      results[0] = (unsigned long)((signed long) ((signed char) (results[3]&0xff)));
      results[1] = (unsigned long)((signed long) ((signed short) (results[3]&0xffff)));
      results[2] = (unsigned long)((signed long) ((signed int) (results[3]&0xffffffff)));
      results[4] = results[3]&0xff;
      results[5] = results[3]&0xffff;
      results[6] = results[3]&0xffffffff;
      gprs[(instruction >> 7) & 31] = results[funct3];
      pc = pc + 4;
      break;

    case 0b11000: // conditional branches
      #ifdef DEBUG
        printf("branch\n");
      #endif
      results[0] = (rs1 == rs2);
      results[1] = (rs1 != rs2);
      results[4] = ((signed long) rs1) < ((signed long) rs2);
      results[5] = ((signed long) rs1) >= ((signed long) rs2);
      results[6] = rs1 < rs2;
      results[7] = rs1 >= rs2;
      pc = pc + (results[funct3] ? immediate_b_type : 4);
      break;

    case 0b11001: // jalr
      #ifdef DEBUG
        printf("jalr\n");
      #endif
      results[0] = rs1;
      gprs[(instruction >> 7) & 31] = pc + 4;
      pc = results[0] + immediate_i_type;
      break;
    
    case 0b11011: // jal
      #ifdef DEBUG
        printf("jal\n");
      #endif
      gprs[(instruction >> 7) & 31] = pc + 4;
      pc = pc + immediate_j_type;
      break;

    case 0b00101: // auipc
      #ifdef DEBUG
        printf("auipc\n");
      #endif
      gprs[(instruction >> 7) & 31] = pc + immediate_u_type;
      pc = pc + 4;
      break;

    case 0b01101: // lui
      #ifdef DEBUG
        printf("lui\n");
      #endif
      gprs[(instruction >> 7) & 31] = immediate_u_type;
      pc = pc + 4;
      break;

    case 0b00110: // op-imm32
      #ifdef DEBUG
        printf("op-imm-32\n");
      #endif
      results[0] = (unsigned long)((signed long)(((signed int)rs1) + ((signed int)immediate_i_type)));
      results[1] = (unsigned long)((signed long)(((signed int)rs1) << (immediate_i_type & 31)));
      results[5] = (unsigned long)((signed long)((instruction & 0x40000000) ? (((signed int) rs1) >> (immediate_i_type & 31)) : ((signed int)((rs1 & 0xFFFFFFFF) >> (immediate_i_type & 31)))));
      
      gprs[(instruction >> 7) & 31] = results[funct3];
      pc = pc + 4;
      break;

    case 0b01110: // op32
      #ifdef DEBUG
        printf("op-32\n");
      #endif
      if(instruction & (1 << 25)) {
        results[0] = (unsigned long)((signed long)(((signed int) rs1) * ((signed int) rs2)));
        results[4] = (unsigned long)((signed int) divw((unsigned int) rs1, (unsigned int) rs2));
        results[5] = (unsigned long)((signed int)((((unsigned int) rs2) == 0U) ? ~0U : ((unsigned int)rs1)/((unsigned int)rs2)));
        results[6] = (unsigned long)((signed int) remw((unsigned int) rs1, (unsigned int) rs2));
        results[7] = (unsigned long)((signed int)((((unsigned int) rs2) == 0U) ? ((unsigned int) rs1) : ((unsigned int)rs1)%((unsigned int)rs2)));
      } else {
        results[0] = (unsigned long)((signed long)((instruction & 0x40000000) ? ((signed int)rs1) - ((signed int)rs2): ((signed int)rs1) + ((signed int)rs2)));
        results[1] = (unsigned long)((signed long)(((signed int)rs1) << (rs2 & 31)));
        results[5] = (unsigned long)((signed long)((instruction & 0x40000000) ? (((signed int) rs1) >> (rs2 & 31)) : ((signed int)((rs1 & 0xFFFFFFFF) >> (rs2 & 31)))));
      }
      gprs[(instruction >> 7) & 31] = results[funct3];
      pc = pc + 4;
      break;

    case 0b00011: // fences
      #ifdef DEBUG
        printf("misc-mem\n");
      #endif
      pc = pc + 4;
      break;

    case 0b11100: // system
      #ifdef DEBUG
        printf("system\n");
      #endif
      if ((instruction >> 12) & 3) {
        csr_read((instruction >> 20) & 0xfff, &results[0]);
        results[4] = (instruction >> 15) & 31;
        gprs[(instruction >> 7) & 31] = results[0];
        results[1] = rs1;
        results[2] = results[0] | rs1;
        results[3] = results[0] & (~rs1);
        results[5] = results[4];
        results[6] = results[0] | results[4];
        results[7] = results[0] & (~results[4]);
        csr_write((instruction >> 20) & 0xfff, results[(instruction >> 12) & 7]);
        pc = pc + 4;
      } else if (((instruction >> 7) == 0) || (instruction == 0x00100073)) {
        // ecall
        csrs[MEPC] = pc;
        if (instruction == 0x00100073) {
          csrs[MCAUSE] = 3;
          csrs[MTVAL] = pc; //instruction;
        } else {
          csrs[MCAUSE] = 8 + priviledge;
          csrs[MTVAL] = 0;
        }
        pc = csrs[MTVEC];
        // setting MPP
        csrs[MSTATUS] = csrs[MSTATUS] & (~(3UL << 11));
        csrs[MSTATUS] = csrs[MSTATUS] | (((unsigned long) priviledge) << 11);
        // setting MPIE
        csrs[MSTATUS] = csrs[MSTATUS] & (~(0b10000000UL));
        csrs[MSTATUS] = csrs[MSTATUS] | ((csrs[MSTATUS] << 4) & (0b10000000UL));
        // setting MIE
        csrs[MSTATUS] = csrs[MSTATUS] & (~(0b1000UL));

        priviledge = 3;
      } else if (instruction == 0x30200073) {
        // mret
        priviledge = (csrs[MSTATUS] >> 11) & 3;
        csrs[MSTATUS] = 0xa00000000 | 0b10000000 | ((csrs[MSTATUS] >> 4) & 0b1000);
        pc = csrs[MEPC];
      } else if (instruction == 0x10500073) { pc += 4; }
      break;

    case 0b01011: // atomics
      switch ((instruction >> 12) & 7) {
      case 3:
        switch ((instruction >> 27) & 3) {
        case 0:
          // need temp place for data from memory (I'm too lazy to create new variables)
          gprs[0] = fetch_long(rs1);//*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]); 
          results[0] = gprs[0] + rs2;
          results[1] = gprs[0] ^ rs2;
          results[2] = gprs[0] | rs2;
          results[3] = gprs[0] & rs2;
          results[4] = (((signed long) gprs[0]) < ((signed long) rs2)) ? gprs[0] : rs2;
          results[5] = (((signed long) gprs[0]) < ((signed long) rs2)) ? rs2 : gprs[0];
          results[6] = ((gprs[0]) < (rs2)) ? gprs[0] : rs2;
          results[7] = ((gprs[0]) < (rs2)) ? rs2 : gprs[0];
          gprs[(instruction >> 7) & 31] = fetch_long(rs1);//*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]);  
          // *reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]) = results[(instruction >> 29) & 7]; 
          write_to_memory(rs1, results[(instruction >> 29) & 7], funct3);
          break;
        
        case 1:
          gprs[(instruction >> 7) & 31] = fetch_long(rs1);//*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]);  
          // *reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]) = rs2; 
          write_to_memory(rs1, rs2, funct3);
          break;

        case 2:
          gprs[(instruction >> 7) & 31] = fetch_long(rs1);//*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]); 
          reservation = {rs1, gprs[(instruction >> 7) & 31], 1, 3};
          break;
        
        case 3:
          if(
            reservation.valid && 
            (reservation.size == 3) && 
            (rs1 == reservation.address) && 
            (fetch_long(rs1) == reservation.data)
          ) {
            // sc.d succeeds
            gprs[(instruction >> 7) & 31] = 0UL;
            //*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]) = rs2;
            write_to_memory(rs1, rs2, funct3);
          } else {
            gprs[(instruction >> 7) & 31] = 1UL;
          }
          reservation.valid = 0;
          break;
        }
        break;
      
      case 2:
      switch ((instruction >> 27) & 3) {
        case 0:
          // need temp place for data from memory (I'm too lazy to create new variables)
          gprs[0] = fetch_long(rs1);//*reinterpret_cast<unsigned long*>(&memory[rs1-RAM_BASE]); 
          results[0] = gprs[0] + rs2;
          results[1] = gprs[0] ^ rs2;
          results[2] = gprs[0] | rs2;
          results[3] = gprs[0] & rs2;
          results[4] = (((signed int) gprs[0]) < ((signed int) rs2)) ? gprs[0] : rs2;
          results[5] = (((signed int) gprs[0]) < ((signed int) rs2)) ? rs2 : gprs[0];
          results[6] = (((unsigned int) gprs[0]) < ((unsigned int) rs2)) ? gprs[0] : rs2;
          results[7] = (((unsigned int) gprs[0]) < ((unsigned int) rs2)) ? rs2 : gprs[0];
          gprs[(instruction >> 7) & 31] = (unsigned long)((signed int)((unsigned int)fetch_long(rs1)));  
          //*reinterpret_cast<unsigned int*>(&memory[rs1-RAM_BASE]) = (unsigned int)results[(instruction >> 29) & 7];  
          write_to_memory(rs1, results[(instruction >> 29) & 7], funct3);
          break;
        
        case 1:
          gprs[(instruction >> 7) & 31] = (unsigned long)((signed int)((unsigned int)fetch_long(rs1)));
          // *reinterpret_cast<unsigned int*>(&memory[rs1-RAM_BASE]) = (unsigned int)rs2;  
          write_to_memory(rs1, rs2, funct3);
          break;
        
        case 2:
          gprs[(instruction >> 7) & 31] = (unsigned long)((signed int)((unsigned int)fetch_long(rs1)));
          reservation = {rs1, gprs[(instruction >> 7) & 31], 1, 2};
          break;
        
        case 3:
          if(
            reservation.valid && 
            (reservation.size == 2) && 
            (rs1 == reservation.address) && 
            (((unsigned int)fetch_long(rs1)) == ((unsigned int) reservation.data))
          ) {
            // sc.w succeeds
            gprs[(instruction >> 7) & 31] = 0UL;
            //*reinterpret_cast<unsigned int*>(&memory[rs1-RAM_BASE]) = (unsigned int)rs2;
            write_to_memory(rs1, rs2, funct3);
          } else {
            gprs[(instruction >> 7) & 31] = 1UL;
          }
          reservation.valid = 0;
          break;
        }
        break;
      }

      pc = pc + 4;
      break;

    default: // nops or illegal instructions
      #ifdef DEBUG
        printf("illegal\n");
      #endif
      pc = pc + 4;
      break;
    }
    long unsigned new_symbol_index = 0;


    // pc = pc + 4;
    /* std::cout << std::setfill('0') << std::setw(8) << std::hex << pc << " instruction: 0x";
    std::cout << std::setfill('0') << std::setw(8) << std::hex << instruction << " mtvec: 0x";
    std::cout << std::setfill('0') << std::setw(8) << std::hex << csrs[MTVEC] << std::endl; */
    return 0;
  }
  
  /**
   * Implements explicit reads to CSR register file
   * Error codes are not implemented
   * 
   * @param csr_address address of accessed csr register
   * @param destination destination of csr read
  */
  int csr_read(int csr_address, unsigned long* destiniation) {
    //printf("a\n");
    switch (csr_address) {
    case MISA:        //ZYXWVUTSRQPONMLKJIHGFEDCBA
      *destiniation = 0b00000100000001000100000001UL | (2UL << 62) ;
      break;

    case MIP:
    case MIE:
    case MHARTID:
      *destiniation = 0;
      break;
    
    default:
      *destiniation = csrs[csr_address];
      break;
    }
    //printf("b\n");
    return 0;
  }

  /**
   * Implements a explicit write to CSR register file
   * Error codes are not implemented
   * 
   * @param csr_address address of accessed csr register
   * @param data data to be written
  */
  int csr_write(int csr_address, unsigned long data) {
    // printf("%016x %016lx \n", csr_address, data);
    switch (csr_address) {
    case MSTATUS:
      if ((((data >> 11) & 3) == 3) || (((data >> 11) & 3) == 3)) {
        // MPP
        csrs[MSTATUS] = csrs[MSTATUS] & (~(3UL << 11));
        csrs[MSTATUS] = csrs[MSTATUS] | (data & (3UL << 11));
      }
      csrs[MSTATUS] = csrs[MSTATUS] & (~(0b10001000UL));
      csrs[MSTATUS] = csrs[MSTATUS] | (data & (0b10001000UL));
      break;
    
    default:
      csrs[csr_address] = data;
      break;
    }

    //printf("d\n");
    return 0;
  }

  /**
   * Prints out the pc of the next executing pc and the values of general
   * purpose registers.
  */
  void show_state() {
    printf("pc: %016lx mstatus: %016lx mie: %016lx mcause: %016lx mepc: %016lx rx_ready: %d\n\
    x00: %016lx x01: %016lx x02: %016lx x03: %016lx x04: %016lx x05: %016lx x06: %016lx x07: %016lx\n\
    x08: %016lx x09: %016lx x10: %016lx x11: %016lx x12: %016lx x13: %016lx x14: %016lx x15: %016lx\n\
    x16: %016lx x17: %016lx x18: %016lx x19: %016lx x20: %016lx x21: %016lx x22: %016lx x23: %016lx\n\
    x24: %016lx x25: %016lx x26: %016lx x27: %016lx x28: %016lx x29: %016lx x30: %016lx x31: %016lx\n",
    pc, csrs[MSTATUS], csrs[MIE], csrs[MCAUSE], csrs[MEPC], rx_ready,
    gprs[0], gprs[1], gprs[2], gprs[3], gprs[4], gprs[5], gprs[6], gprs[7], 
    gprs[8], gprs[9], gprs[10], gprs[11], gprs[12], gprs[13], gprs[14], gprs[15],
    gprs[16], gprs[17], gprs[18], gprs[19], gprs[20], gprs[21], gprs[22], gprs[23],
    gprs[24], gprs[25], gprs[26], gprs[27], gprs[28], gprs[29], gprs[30], gprs[31]);
  }

  /**
   * Virtuall all(at least for now) riscv-tests write the results to
   * (starting-pc + 0x1000). Performs a read on that address
  */
  unsigned int check_test_status() {
    return *reinterpret_cast<unsigned int*>(&memory[0x1000]);
  }

  /**
   * Executes mulh(riscv instruction) on two operands.
  */
  unsigned long mulh(unsigned long rs1, unsigned long rs2) {
    unsigned long partial_products[4];
    // rs1[low] * rs2[low]
    partial_products[0] = (rs1 & (~((~0UL) << 32))) * (rs2 & (~((~0UL) << 32)));
    // rs1[low] * rs2[high]
    partial_products[1] = (unsigned long) (((signed long) (rs1 & (~((~0UL) << 32)))) * (((signed long) rs2) >> 32));
    // rs1[high] * rs2[low]
    partial_products[2] = (unsigned long) (((signed long) (rs2 & (~((~0UL) << 32)))) * (((signed long) rs1) >> 32));
    // rs1[high] * rs2[high]
    partial_products[3] = (unsigned long) (((signed long) rs1) >> 32) * (((signed long) rs2) >> 32);
    partial_products[3] =  partial_products[3] + (unsigned long)(((signed long)  partial_products[2]) >> 32) + (unsigned long)(((signed long)  partial_products[1]) >> 32); 
    partial_products[0] = (partial_products[0] >> 32) + (partial_products[1] & (~((~0UL) << 32))) + (partial_products[2] & (~((~0UL) << 32)));
    return partial_products[3] + (partial_products[0] >> 32);
  }

  /**
   * Executes mulhu(riscv instruction) on two operands.
  */
  unsigned long mulhu(unsigned long rs1, unsigned long rs2) {
    unsigned long partial_products[4];
    // rs1[low] * rs2[low]
    partial_products[0] = (rs1 & (~((~0UL) << 32))) * (rs2 & (~((~0UL) << 32)));
    // rs1[low] * rs2[high]
    partial_products[1] = (unsigned long) (((rs1 & (~((~0UL) << 32)))) * ((rs2) >> 32));
    // rs1[high] * rs2[low]
    partial_products[2] = (unsigned long) (((rs2 & (~((~0UL) << 32)))) * ((rs1) >> 32));
    // rs1[high] * rs2[high]
    partial_products[3] = (unsigned long) ((rs1) >> 32) * ((rs2) >> 32);
    partial_products[3] =  partial_products[3] + (unsigned long)(( partial_products[2]) >> 32) + (unsigned long)(( partial_products[1]) >> 32); 
    partial_products[0] = (partial_products[0] >> 32) + (partial_products[1] & (~((~0UL) << 32))) + (partial_products[2] & (~((~0UL) << 32)));
    return partial_products[3] + (partial_products[0] >> 32);
  }

  /**
   * Executes mulhsu(riscv instruction) on two operands.
  */
  unsigned long mulhsu(unsigned long rs1, unsigned long rs2) {
    unsigned long partial_products[4];
    // rs1[low] * rs2[low]
    partial_products[0] = (rs1 & (~((~0UL) << 32))) * (rs2 & (~((~0UL) << 32)));
    // rs1[low] * rs2[high]
    partial_products[1] = (unsigned long) (((rs1 & (~((~0UL) << 32)))) * ((rs2) >> 32));
    // rs1[high] * rs2[low]
    partial_products[2] = (unsigned long) (((signed long) (rs2 & (~((~0UL) << 32)))) * (((signed long) rs1) >> 32));
    // rs1[high] * rs2[high]
    partial_products[3] = (unsigned long) (((signed long) rs1) >> 32) * ((signed long)((rs2) >> 32));
    partial_products[3] =  partial_products[3] + (unsigned long)(((signed long)  partial_products[2]) >> 32) + (unsigned long)((partial_products[1]) >> 32); 
    partial_products[0] = (partial_products[0] >> 32) + (partial_products[1] & (~((~0UL) << 32))) + (partial_products[2] & (~((~0UL) << 32)));
    return partial_products[3] + (partial_products[0] >> 32);
  }

  /**
   * Executes div(riscv instruction) on two operands.
  */
  unsigned long div(unsigned long rs1, unsigned long rs2) {
    //return 0UL;
    unsigned long absolute_rs1 = (rs1 >> 63) ? ((~rs1) + 1UL) : rs1;
    unsigned long absolute_rs2 = (rs2 >> 63) ? ((~rs2) + 1UL) : rs2;

    // handling zero division
    if (rs2 == 0UL)
      return ~0UL;
    
    switch ((rs1 >> 63) + (rs2 >> 63)){
    case 0:
      return (absolute_rs1/absolute_rs2);

    case 1:
      return 0UL - (absolute_rs1/absolute_rs2);

    case 2:
      return (absolute_rs1/absolute_rs2);
    } 
    return 0UL;
  }

  /**
   * Executes rem(riscv instruction) on two operands.
  */
  unsigned long rem(unsigned long rs1, unsigned long rs2) {
    //return 0UL;
    unsigned long absolute_rs1 = (rs1 >> 63) ? ((~rs1) + 1UL) : rs1;
    unsigned long absolute_rs2 = (rs2 >> 63) ? ((~rs2) + 1UL) : rs2;

    if (rs2 == 0UL)
      return rs1;
    
    switch ((rs1 >> 63)){
    case 0:
      return (absolute_rs1%absolute_rs2);

    case 1:
      return 0UL - (absolute_rs1%absolute_rs2);
    } 

    return 0UL;
  }

  /**
   * Executes divw(riscv instruction) on two operands.
  */
  unsigned int divw(unsigned int rs1, unsigned int rs2) {
    //return 0UL;
    unsigned long absolute_rs1 = (rs1 >> 31) ? ((~rs1) + 1U) : rs1;
    unsigned long absolute_rs2 = (rs2 >> 31) ? ((~rs2) + 1U) : rs2;

    // handling zero division
    if (rs2 == 0U)
      return ~0U;
    
    switch ((rs1 >> 31) + (rs2 >> 31)){
    case 0:
      return (absolute_rs1/absolute_rs2);

    case 1:
      return 0U - (absolute_rs1/absolute_rs2);

    case 2:
      return (absolute_rs1/absolute_rs2);
    } 
    return 0U;
  }

  /**
   * Executes rem(riscv instruction) on two operands.
  */
  unsigned int remw(unsigned int rs1, unsigned int rs2) {
    //return 0UL;
    unsigned int absolute_rs1 = (rs1 >> 31) ? ((~rs1) + 1UL) : rs1;
    unsigned int absolute_rs2 = (rs2 >> 31) ? ((~rs2) + 1UL) : rs2;

    if (rs2 == 0U)
      return rs1;
    
    switch ((rs1 >> 31)){
    case 0:
      return (absolute_rs1%absolute_rs2);

    case 1:
      return 0U - (absolute_rs1%absolute_rs2);
    } 

    return 0U;
  }

  /**
   * Loads dibugging symbols to be used for debugging
   * 
   * @param symbols_list_file contains the names of the symbols
   * @param symbols_pointer_file contains the pointers of the corrosponding symbols
  */
  void load_symbols(std::string symbols_list_file, std::string symbols_pointer_file) {
    std::ifstream inputFile(symbols_list_file);

    if (!inputFile.is_open()) {
      std::cerr << "Failed to open the file." << std::endl;
      return;
    }

    std::string line;
    while (std::getline(inputFile, line)) {
      symbols.push_back(line);  // Store each line in the vector
    }

    inputFile.close();

    std::ifstream inputFile2(symbols_pointer_file, std::ios::binary);

    if (!inputFile2.is_open()) {
      std::cerr << "Failed to open the file." << std::endl;
      return;
    }

    // Get the file size by seeking to the end and then getting the position
    inputFile2.seekg(0, std::ios::end);
    std::streampos fileSize = inputFile2.tellg();
    inputFile2.seekg(0, std::ios::beg);

    // Create a vector to store the binary data
    std::vector<unsigned long> binaryData(fileSize);

    // Read the binary data into the vector
    inputFile2.read(reinterpret_cast<char*>(binaryData.data()), fileSize);

    inputFile2.close();
    unsigned long pointer_end = (fileSize/8) - 1;
    unsigned long i = 0;
    for (const unsigned long address : binaryData) {
      symbol_pointers.push_back(static_cast<unsigned long>(address));
      //printf("%lx\n", static_cast<unsigned long>(address));
      if ((i++) >= pointer_end)
      {
        break;
      }
      
    }
  }

};
