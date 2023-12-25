# FYP-18-RISCV-Core
## Prerequisites
Verilator
```
sudo apt-get -y install verilator
```
Get submodules
```
git submodule update --init --recursive
```
## Implementing the core
### Simulation
Generate the verilog and run verilator
```
make sim
```
Running simulation (without a emulator to verify correct operation)
```
make runSim
```
Running simulation (with a emulator to verify correct operation)
```
make runLockStep 
```
### FPGA (Zynq 7000 SoC)
1. Generate the verilog sources
  ```
  make fpga
  ```
2. Create a new project on Vivado (Has only been tested for dev boards zedboard and zybo-z7-20)
3. Import the following files to project
  ```
  core.v
  psClint.v
  bootROM.v
  src/main/resources/zynq/dCacheRegisters.v
  src/main/resources/zynq/iCacheRegisters.v
  ```
4. Generate the following ```Block RAM Memory Generator``` IPs
  ```
  Component Name: blk_d_cache
    Basic
      Interface Type: Native 
      Memory Type: Simple Dual Port RAM
      Generate address interface with 32 bits: Not selected
      Common clock: Not selected
      ECC
        ECC Type: No ECC
      Write Enable
        Byte Write Enable: Selected
        Byte Size(bits): 8
      Algorithm Options
        Algorithm: Minimum Area
    Port A Options
      Memory size
        Port A Width: 512
        Port A Depth: 64
        Operating Mode: No change
        Enable Port Type: Always Enabled
    Port B Options
      Memory Size
        Port B Width: 128
        Port B Depth: 256
        Operating Mode: Write First
        Enable Port Type: Always Enabled
      Port B Optional Output Registers
        Primitives Output Register: Not selected
        Core Output Register: Not selected
      Port B Output Reset Options
        RSTB Pin: Not selected

  Component Name: blk_d_cache_tags
    Basic
      Interface Type: Native 
      Memory Type: Simple Dual Port RAM
      Generate address interface with 32 bits: Not selected
      Common clock: Not selected
      ECC
        ECC Type: No ECC
      Write Enable
        Byte Write Enable: Not selected
      Algorithm Options
        Algorithm: Minimum Area
    Port A Options
      Memory size
        Port A Width: 20
        Port A Depth: 64
        Operating Mode: No change
        Enable Port Type: Always Enabled
    Port B Options
      Memory Size
        Port B Width: 20
        Port B Depth: 64
        Operating Mode: Write First
        Enable Port Type: Always Enabled
      Port B Optional Output Registers
        Primitives Output Register: Not selected
        Core Output Register: Not selected
      Port B Output Reset Options
        RSTB Pin: Not selected
  ```
5. Create new block diagram
6. Create Zynq-PS IP
  ```
  UART1: Enabled
  M AXI GP0 interface: Enabled to signal boot program when the RAM is ready
  S AXI GP0 interface: Enabled to allow uart access from RISCV core
  S AXI HP0 interface: Enabled to allow RAM access from RISCV core
  FCLK_CLK0: 55 MHz
  ```
7. Import ```core.v``` and ```psClint.v``` to block diagram (Note: Both the reset signals are active high)
8. ```Address Editor``` should show the following
  ```
  Zynq-PS
    M AXI GP0:
      0x4000_0000 - 0x7FFF_FFFF: psMaster port of psClint
  core.v
    iPort:
      0x0000_0000 - 0x3FFF_FFFF: S_AXI_HP0 port of Zynq-PS
      0x4000_0000 - 0x7FFF_FFFF: req port of bootROM
    dPort:
      0x0000_0000 - 0x3FFF_FFFF: S_AXI_HP0 port of Zynq-PS
      0x4000_0000 - 0x7FFF_FFFF: req port of bootROM
    peripheral:
      0x0000_0000 - 0x0FFF_FFFF: client port of psClint
      0xE000_0000 - 0xEO3F_FFFF: S_AXI_GP0 port of Zynq-PS
  ```
9. Connect MTIP of ```core.v``` and ```psClint.v```
10. Build bitstream, export hardware and open new Vitis project