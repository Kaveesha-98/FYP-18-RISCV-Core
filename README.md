# FYP-18-RISCV-Core
## Prerequisites
1. Verilator
```
sudo apt-get -y install verilator
```
2. Get submodules
```
git submodule update --init --recursive
```
3. vivado v2019.2
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
2. [Get board files for zybo-z7-20](https://digilent.com/reference/programmable-logic/guides/install-board-files)
3. Create a new project on Vivado in the repo root folder (Has only been tested for dev board zybo-z7-20)
4. Run the command in the tcl console
  ```
  source vivado.tcl
  ```
