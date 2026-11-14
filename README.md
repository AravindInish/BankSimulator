# BankSimulator

A Java Swing desktop banking simulator for managing multiple savings/current accounts, transactions, balance tracking, and exportable statements.

## Overview

BankSimulator is a mock banking application designed to demonstrate core banking behaviors in a clean desktop UI. It includes account management, PIN-protected actions, daily transaction limits, savings account rules, monthly interest, live balance trends, and CSV statement export.

The project is implemented as a single-file Java application with a polished dashboard interface and sample seeded data for instant demo use.

## Features

- Multi-account banking dashboard
- Savings and Current account types
- PIN-protected deposit, withdrawal, and transfer flows
- Daily debit limit enforcement
- Minimum balance validation for savings accounts
- Monthly interest calculation for savings accounts
- Live balance trend chart with hover details
- Searchable, sortable transaction ledger
- CSV statement export
- Auto-save to local data file
- Demo data reset option

## Demo

Sample demo PIN for all seeded accounts:

- 1234

## Tech Stack

- Java
- Swing / AWT
- Java standard library

## Requirements

- JDK 11 or later
- A desktop environment (Windows/macOS/Linux GUI session)

## Run the Project

From the project directory:

```bash
javac BankSim.java
java BankSim
```

## How it Works

- Accounts are stored in memory and persisted locally to `banksim_data.ser`
- Each account contains a balance, transaction history, and PIN
- Savings accounts must maintain a minimum balance
- Daily withdrawals and transfers are checked against the configured limit
- Statement export generates a CSV file for the selected account

## Project Structure

```text
BankSimulator/
├── BankSim.java
├── README.md
└── banksim_data.ser   # generated at runtime
```

## Keyboard Shortcuts

- D: Deposit
- W: Withdraw
- T: Transfer
- I: Apply monthly interest
- N: New account
- P: Change PIN
- H: Hide/Show balance
- E: Export statement
- Q: Exit

## Notes

This application is intended for educational/demo purposes and is not a real banking or financial system.

## License

This project is available for learning and personal use.
