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
