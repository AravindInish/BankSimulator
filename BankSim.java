import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.*;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * BankSim - Core Banking Simulation (Blue & White edition)
 *
 * Features:
 *  - Dashboard with gradient balance card, hide/show balance, daily-limit meter
 *  - Deposit / Withdraw / Transfer dialogs with quick-amount chips and PIN checks
 *  - Savings (min balance) and Current accounts, monthly interest on Savings
 *  - Live balance-trend chart with hover tooltips
 *  - Searchable, filterable, sortable ledger + CSV statement export
 *  - Auto-save to disk, keyboard shortcuts, status-bar notifications
 *
 * Demo PIN for the sample accounts: 1234
 * Requires JDK 11+.
 */
public class BankSim extends JFrame {

    private static final double MAX_TX = 10_000_000;

    private final Bank bank = Bank.load();
    private Account current;
    private boolean balanceHidden = false;
    private boolean updatingCombo = false;

    // Header
    private JComboBox<String> cbAccounts;
    private JLabel lblClock;

    // Left column
    private JLabel lblBalance, lblHolder, lblNumber, lblLimitText, lblIn, lblOut, lblCount;
    private JButton btnHide;
    private MeterBar meter;
    private RoundedButton btnInterest;

    // Right column
    private TrendChart chart;
    private TxModel txModel;
    private JTable table;
    private TableRowSorter<TxModel> sorter;
    private JTextField txtSearch;
    private JComboBox<String> cbFilter;

    // Status bar
    private JLabel lblStatus;
    private javax.swing.Timer statusTimer;

    public BankSim() {
        super("BankSim \u2014 Core Banking Simulation");
        current = bank.accounts.get(0);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exitApp(); }
        });
        setSize(1120, 740);
        setMinimumSize(new Dimension(1000, 660));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout());

        setJMenuBar(buildMenuBar());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        reloadAccounts();
        refresh();
        startClock();
    }

    // =====================================================================
    //  UI CONSTRUCTION
    // =====================================================================

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.setBackground(Color.WHITE);
        mb.setBorder(new MatteBorder(0, 0, 1, 0, Theme.LINE));

        JMenu file = menu("File");
        file.add(item("Export Statement (CSV)\u2026", KeyEvent.VK_E, e -> exportCsv()));
        file.add(item("Reset Demo Data", 0, e -> resetData()));
        file.addSeparator();
        file.add(item("Exit", KeyEvent.VK_Q, e -> exitApp()));

        JMenu tx = menu("Transactions");
        tx.add(item("Deposit", KeyEvent.VK_D, e -> showTransactionDialog("Deposit")));
        tx.add(item("Withdraw", KeyEvent.VK_W, e -> showTransactionDialog("Withdraw")));
        tx.add(item("Transfer", KeyEvent.VK_T, e -> showTransactionDialog("Transfer")));
        tx.addSeparator();
        tx.add(item("Apply Monthly Interest", KeyEvent.VK_I, e -> applyInterest()));

        JMenu acc = menu("Account");
        acc.add(item("New Account\u2026", KeyEvent.VK_N, e -> showNewAccountDialog()));
        acc.add(item("Change PIN\u2026", KeyEvent.VK_P, e -> showChangePinDialog()));
        acc.add(item("Hide / Show Balance", KeyEvent.VK_H, e -> toggleBalance()));

        JMenu help = menu("Help");
        help.add(item("About BankSim", 0, e -> showAbout()));

        mb.add(file);
        mb.add(tx);
        mb.add(acc);
        mb.add(help);
        return mb;
    }

    private JMenu menu(String text) {
        JMenu m = new JMenu(text);
        m.setFont(Theme.font(Font.PLAIN, 13));
        return m;
    }

    private JMenuItem item(String text, int key, ActionListener al) {
        JMenuItem mi = new JMenuItem(text);
        mi.setFont(Theme.font(Font.PLAIN, 13));
        if (key != 0) {
            mi.setAccelerator(KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        mi.addActionListener(al);
        return mi;
    }

    private JComponent buildHeader() {
        RoundedPanel header = new RoundedPanel(new BorderLayout(), 0, Theme.DARK, Theme.PRIMARY, false);
        header.setBorder(new EmptyBorder(14, 24, 14, 24));

        JPanel left = new JPanel(new GridLayout(2, 1));
        left.setOpaque(false);
        left.add(Theme.label("BankSim", 26, Font.BOLD, Color.WHITE));
        left.add(Theme.label("Core Banking Simulation", 12, Font.PLAIN, Theme.SOFT));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 6));
        right.setOpaque(false);
        lblClock = Theme.label("", 12, Font.PLAIN, Theme.SOFT);

        cbAccounts = new JComboBox<>();
        cbAccounts.setFont(Theme.font(Font.PLAIN, 13));
        cbAccounts.setPreferredSize(new Dimension(260, 34));
        cbAccounts.setToolTipText("Switch active account");
        cbAccounts.addActionListener(e -> {
            if (updatingCombo) return;
            int i = cbAccounts.getSelectedIndex();
            if (i >= 0) {
                current = bank.accounts.get(i);
                refresh();
            }
        });

        RoundedButton btnNew = new RoundedButton("+ New Account", Color.WHITE, Theme.PRIMARY);
        btnNew.addActionListener(e -> showNewAccountDialog());

        right.add(lblClock);
        right.add(cbAccounts);
        right.add(btnNew);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(18, 20, 10, 20));
        body.add(buildLeft(), BorderLayout.WEST);
        body.add(buildRight(), BorderLayout.CENTER);
        return body;
    }

    // ---------------- LEFT COLUMN ----------------

    private JComponent buildLeft() {
        JPanel col = new JPanel(new GridBagLayout());
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(350, 0));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 16, 0);

        gc.gridy = 0; col.add(buildBalanceCard(), gc);
        gc.gridy = 1; col.add(buildActions(), gc);
        gc.gridy = 2; col.add(buildStats(), gc);

        JPanel filler = new JPanel();
        filler.setOpaque(false);
        gc.gridy = 3; gc.weighty = 1; gc.fill = GridBagConstraints.BOTH;
        col.add(filler, gc);
        return col;
    }

    private JComponent buildBalanceCard() {
        RoundedPanel card = new RoundedPanel(new BorderLayout(), 22, Theme.DARK, Theme.ACCENT, true);

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel cap = Theme.label("AVAILABLE BALANCE", 11, Font.BOLD, Theme.SOFT);
        lblBalance = Theme.label("\u20B90.00", 30, Font.BOLD, Color.WHITE);
        btnHide = Theme.linkButton("Hide");
        btnHide.addActionListener(e -> toggleBalance());

        JPanel balRow = new JPanel(new BorderLayout());
        balRow.setOpaque(false);
        balRow.add(lblBalance, BorderLayout.WEST);
        balRow.add(btnHide, BorderLayout.EAST);

        lblHolder = Theme.label(" ", 17, Font.BOLD, Color.WHITE);
        lblNumber = Theme.label(" ", 12, Font.PLAIN, Theme.SOFT);
        lblLimitText = Theme.label(" ", 11, Font.PLAIN, Theme.SOFT);
        meter = new MeterBar();

        for (JComponent c : new JComponent[]{cap, balRow, lblHolder, lblNumber, lblLimitText, meter}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        box.add(cap);
        box.add(Box.createVerticalStrut(4));
        box.add(balRow);
        box.add(Box.createVerticalStrut(18));
        box.add(lblHolder);
        box.add(Box.createVerticalStrut(2));
        box.add(lblNumber);
        box.add(Box.createVerticalStrut(18));
        box.add(lblLimitText);
        box.add(Box.createVerticalStrut(6));
        box.add(meter);

        card.add(box, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildActions() {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 12), 18, Color.WHITE, null, true);
        card.setBorder(new EmptyBorder(16, 18, 18, 18));
        card.add(Theme.label("Quick Actions", 14, Font.BOLD, Theme.TEXT), BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 2, 10, 10));
        grid.setOpaque(false);

        RoundedButton dep = new RoundedButton("Deposit", Theme.PRIMARY, Color.WHITE);
        RoundedButton wd = new RoundedButton("Withdraw", Theme.LIGHT, Theme.PRIMARY);
        RoundedButton tr = new RoundedButton("Transfer", Theme.PRIMARY, Color.WHITE);
        btnInterest = new RoundedButton("Add Interest", Theme.LIGHT, Theme.PRIMARY);

        dep.addActionListener(e -> showTransactionDialog("Deposit"));
        wd.addActionListener(e -> showTransactionDialog("Withdraw"));
        tr.addActionListener(e -> showTransactionDialog("Transfer"));
        btnInterest.addActionListener(e -> applyInterest());
        btnInterest.setToolTipText("Credit one month of interest (Savings accounts only)");

        grid.add(dep);
        grid.add(wd);
        grid.add(tr);
        grid.add(btnInterest);
        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    private JComponent buildStats() {
        lblIn = new JLabel("\u20B90");
        lblOut = new JLabel("\u20B90");
        lblCount = new JLabel("0");

        JPanel row = new JPanel(new GridLayout(1, 3, 10, 0));
        row.setOpaque(false);
        row.add(statCard("MONEY IN", lblIn, Theme.GREEN));
        row.add(statCard("MONEY OUT", lblOut, Theme.RED));
        row.add(statCard("TRANSACTIONS", lblCount, Theme.PRIMARY));
        return row;
    }

    private RoundedPanel statCard(String title, JLabel value, Color color) {
        RoundedPanel p = new RoundedPanel(new BorderLayout(0, 4), 16, Color.WHITE, null, true);
        p.setBorder(new EmptyBorder(12, 14, 14, 14));
        value.setFont(Theme.font(Font.BOLD, 16));
        value.setForeground(color);
        p.add(Theme.label(title, 10, Font.BOLD, Theme.MUTED), BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    // ---------------- RIGHT COLUMN ----------------

    private JComponent buildRight() {
        JPanel col = new JPanel(new BorderLayout(0, 16));
        col.setOpaque(false);

        // Chart card
        RoundedPanel chartCard = new RoundedPanel(new BorderLayout(0, 6), 18, Color.WHITE, null, true);
        chartCard.setBorder(new EmptyBorder(14, 18, 12, 18));
        chartCard.setPreferredSize(new Dimension(0, 230));
        JPanel ct = new JPanel(new BorderLayout());
        ct.setOpaque(false);
        ct.add(Theme.label("Balance Trend", 14, Font.BOLD, Theme.TEXT), BorderLayout.WEST);
        ct.add(Theme.label("Last 15 transactions \u00B7 hover for details", 11, Font.PLAIN, Theme.MUTED), BorderLayout.EAST);
        chart = new TrendChart();
        chartCard.add(ct, BorderLayout.NORTH);
        chartCard.add(chart, BorderLayout.CENTER);

        // Ledger card
        RoundedPanel ledger = new RoundedPanel(new BorderLayout(0, 10), 18, Color.WHITE, null, true);
        ledger.setBorder(new EmptyBorder(14, 18, 16, 18));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Theme.label("Transaction Ledger", 14, Font.BOLD, Theme.TEXT), BorderLayout.WEST);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.setOpaque(false);
        txtSearch = Theme.field(14);
        txtSearch.setToolTipText("Search by description, note, amount or date");
        txtSearch.getDocument().addDocumentListener((DocChange) () -> applyFilter());
        cbFilter = new JComboBox<>(new String[]{"All transactions", "Credits only", "Debits only"});
        cbFilter.setFont(Theme.font(Font.PLAIN, 12));
        cbFilter.addActionListener(e -> applyFilter());
        RoundedButton btnExport = new RoundedButton("Export CSV", Theme.LIGHT, Theme.PRIMARY);
        btnExport.setBorder(new EmptyBorder(6, 12, 6, 12));
        btnExport.addActionListener(e -> exportCsv());
        tools.add(Theme.label("Search", 12, Font.PLAIN, Theme.MUTED));
        tools.add(txtSearch);
        tools.add(cbFilter);
        tools.add(btnExport);
        top.add(tools, BorderLayout.EAST);

        txModel = new TxModel();
        table = new JTable(txModel);
        sorter = new TableRowSorter<>(txModel);
        table.setRowSorter(sorter);
        table.setRowHeight(34);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(Theme.SELECT);
        table.setSelectionForeground(Theme.TEXT);

        LedgerRenderer renderer = new LedgerRenderer();
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);

        JTableHeader th = table.getTableHeader();
        th.setReorderingAllowed(false);
        th.setPreferredSize(new Dimension(0, 38));
        th.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                String text = String.valueOf(v);
                List<? extends RowSorter.SortKey> keys = t.getRowSorter() == null ? null : t.getRowSorter().getSortKeys();
                if (keys != null && !keys.isEmpty() && keys.get(0).getColumn() == t.convertColumnIndexToModel(c)) {
                    text += keys.get(0).getSortOrder() == SortOrder.ASCENDING ? "  \u25B2" : "  \u25BC";
                }
                setText(text);
                setOpaque(true);
                setBackground(Theme.PRIMARY);
                setForeground(Color.WHITE);
                setFont(Theme.font(Font.BOLD, 12));
                setBorder(new EmptyBorder(0, 12, 0, 12));
                setHorizontalAlignment(c >= 2 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return this;
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(new LineBorder(Theme.LINE, 1, true));
        sp.getViewport().setBackground(Color.WHITE);

        ledger.add(top, BorderLayout.NORTH);
        ledger.add(sp, BorderLayout.CENTER);

        col.add(chartCard, BorderLayout.NORTH);
        col.add(ledger, BorderLayout.CENTER);
        return col;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.LINE), new EmptyBorder(7, 20, 7, 20)));
        lblStatus = Theme.label("Ready", 12, Font.PLAIN, Theme.MUTED);
        bar.add(lblStatus, BorderLayout.WEST);
        bar.add(Theme.label("Auto-saved  \u00B7  Demo PIN for sample accounts: 1234", 11, Font.PLAIN, Theme.MUTED), BorderLayout.EAST);
        return bar;
    }

    // =====================================================================
    //  STATE REFRESH
    // =====================================================================

    private void reloadAccounts() {
        updatingCombo = true;
        cbAccounts.removeAllItems();
        for (Account a : bank.accounts) {
            cbAccounts.addItem(a.number + " - " + a.holder);
        }
        cbAccounts.setSelectedIndex(bank.accounts.indexOf(current));
        updatingCombo = false;
    }

    private void refresh() {
        lblBalance.setText(balanceHidden ? "\u20B9 \u2022\u2022\u2022\u2022\u2022\u2022" : Theme.money(current.balance));
        btnHide.setText(balanceHidden ? "Show" : "Hide");
        lblHolder.setText(current.holder);
        lblNumber.setText(current.number + "  \u00B7  " + current.type + " Account");

        double used = current.todaysDebits();
        lblLimitText.setText("Daily withdrawal limit:  " + Theme.money(used) + " / " + Theme.money(Account.DAILY_LIMIT));
        meter.setFraction(used / Account.DAILY_LIMIT);

        double in = 0, out = 0;
        for (Transaction t : current.txs) {
            if (t.credit) in += t.amount; else out += t.amount;
        }
        lblIn.setText(Theme.compact(in));
        lblOut.setText(Theme.compact(out));
        lblIn.setToolTipText(Theme.money(in));
        lblOut.setToolTipText(Theme.money(out));
        lblCount.setText(String.valueOf(current.txs.size()));

        btnInterest.setEnabled(current.type.equals("Savings"));
        txModel.setData(current.txs);
        chart.setData(current.txs);
    }

    private void toggleBalance() {
        balanceHidden = !balanceHidden;
        refresh();
    }

    private void applyFilter() {
        final String q = txtSearch.getText().trim().toLowerCase();
        final int f = cbFilter.getSelectedIndex();
        sorter.setRowFilter(new RowFilter<TxModel, Integer>() {
            @Override
            public boolean include(Entry<? extends TxModel, ? extends Integer> e) {
                Transaction t = e.getModel().get(e.getIdentifier());
                if (f == 1 && !t.credit) return false;
                if (f == 2 && t.credit) return false;
                if (q.isEmpty()) return true;
                return t.type.toLowerCase().contains(q)
                        || t.note.toLowerCase().contains(q)
                        || Theme.money(t.amount).toLowerCase().contains(q)
                        || Theme.DT.format(t.time).toLowerCase().contains(q);
            }
        });
    }

    private void startClock() {
        lblClock.setText(LocalDateTime.now().format(Theme.CLOCK));
        new javax.swing.Timer(1000, e -> lblClock.setText(LocalDateTime.now().format(Theme.CLOCK))).start();
    }

    private void toast(String msg, boolean error) {
        lblStatus.setText((error ? "\u2716  " : "\u2714  ") + msg);
        lblStatus.setForeground(error ? Theme.RED : Theme.GREEN);
        if (statusTimer != null) statusTimer.stop();
        statusTimer = new javax.swing.Timer(5000, e -> {
            lblStatus.setText("Ready");
            lblStatus.setForeground(Theme.MUTED);
        });
        statusTimer.setRepeats(false);
        statusTimer.start();
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Please check", JOptionPane.WARNING_MESSAGE);
    }

    // =====================================================================
    //  ACTIONS
    // =====================================================================

    private void showTransactionDialog(String kind) {
        boolean transfer = kind.equals("Transfer");
        boolean needPin = !kind.equals("Deposit");

        List<Account> others = new ArrayList<>();
        for (Account a : bank.accounts) if (a != current) others.add(a);
        if (transfer && others.isEmpty()) {
            toast("Create another account first to transfer money.", true);
            return;
        }

        JTextField amount = Theme.field(14);
        JTextField note = Theme.field(14);
        JPasswordField pin = new JPasswordField(8);
        Theme.style(pin);
        JComboBox<String> target = new JComboBox<>();
        target.setFont(Theme.font(Font.PLAIN, 13));
        for (Account a : others) target.addItem(a.number + "  \u00B7  " + a.holder);

        JPanel p = Theme.form();
        int y = 0;
        Theme.row(p, y++, "Account", new JLabel(current.number + "  \u00B7  " + current.holder));
        Theme.row(p, y++, "Available", Theme.label(Theme.money(current.balance), 13, Font.BOLD, Theme.PRIMARY));
        if (transfer) Theme.row(p, y++, "Transfer to", target);
        Theme.row(p, y++, "Amount (\u20B9)", amount);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        for (int v : new int[]{500, 1000, 2000, 5000}) {
            RoundedButton chip = new RoundedButton("\u20B9" + v, Theme.LIGHT, Theme.PRIMARY);
            chip.setFont(Theme.font(Font.BOLD, 11));
            chip.setBorder(new EmptyBorder(4, 10, 4, 10));
            chip.addActionListener(e -> amount.setText(String.valueOf(v)));
            chips.add(chip);
        }
        Theme.row(p, y++, "Quick pick", chips);
        Theme.row(p, y++, "Note (optional)", note);
        if (needPin) Theme.row(p, y++, "PIN", pin);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, kind, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;

            String raw = amount.getText().trim().replace(",", "");
            if (!raw.matches("\\d+(\\.\\d{1,2})?")) {
                error("Please enter a valid amount (up to 2 decimal places).");
                continue;
            }
            double amt = Theme.round2(Double.parseDouble(raw));
            if (amt <= 0) {
                error("Amount must be greater than zero.");
                continue;
            }
            if (amt > MAX_TX) {
                error("A single transaction cannot exceed " + Theme.money(MAX_TX) + ".");
                continue;
            }
            if (needPin && !new String(pin.getPassword()).equals(current.pin)) {
                error("Incorrect PIN. Please try again.");
                pin.setText("");
                continue;
            }
            if (!kind.equals("Deposit")) {
                String err = current.checkDebit(amt);
                if (err != null) {
                    error(err);
                    continue;
                }
            }

            String n = note.getText().trim();
            switch (kind) {
                case "Deposit":
                    current.credit(amt, "Deposit", n);
                    toast("Deposited " + Theme.money(amt) + " successfully.", false);
                    break;
                case "Withdraw":
                    current.debit(amt, "Withdrawal", n);
                    toast("Withdrew " + Theme.money(amt) + " successfully.", false);
                    break;
                default:
                    Account t = others.get(target.getSelectedIndex());
                    current.debit(amt, "Transfer to " + t.number, n);
                    t.credit(amt, "Transfer from " + current.number, n);
                    toast("Transferred " + Theme.money(amt) + " to " + t.holder + " (" + t.number + ").", false);
            }
            bank.save();
            refresh();
            return;
        }
    }

    private void applyInterest() {
        if (!current.type.equals("Savings")) {
            toast("Interest is only available on Savings accounts.", true);
            return;
        }
        double interest = Theme.round2(current.balance * Account.INTEREST_RATE / 12);
        if (interest <= 0) {
            toast("No interest to credit on a zero balance.", true);
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "Credit one month of interest at " + (Account.INTEREST_RATE * 100) + "% p.a.?\n\nAmount: " + Theme.money(interest),
                "Apply Interest", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        current.credit(interest, "Interest Credit", "Monthly @ " + (Account.INTEREST_RATE * 100) + "% p.a.");
        bank.save();
        refresh();
        toast("Interest of " + Theme.money(interest) + " credited.", false);
    }

    private void showNewAccountDialog() {
        JTextField name = Theme.field(16);
        JComboBox<String> type = new JComboBox<>(new String[]{"Savings", "Current"});
        type.setFont(Theme.font(Font.PLAIN, 13));
        JTextField opening = Theme.field(10);
        opening.setText("1000");
        JPasswordField pin = new JPasswordField(6);
        JPasswordField pin2 = new JPasswordField(6);
        Theme.style(pin);
        Theme.style(pin2);

        JPanel p = Theme.form();
        int y = 0;
        Theme.row(p, y++, "Holder name", name);
        Theme.row(p, y++, "Account type", type);
        Theme.row(p, y++, "Opening deposit (\u20B9)", opening);
        Theme.row(p, y++, "Set 4-digit PIN", pin);
        Theme.row(p, y++, "Confirm PIN", pin2);
        Theme.row(p, y++, "", Theme.label("Savings accounts must keep a minimum balance of " + Theme.money(Account.SAVINGS_MIN) + ".", 11, Font.PLAIN, Theme.MUTED));

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, "Open New Account", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;

            String nm = name.getText().trim();
            if (nm.isEmpty() || nm.length() > 30) {
                error("Please enter a holder name (1\u201330 characters).");
                continue;
            }
            String raw = opening.getText().trim().replace(",", "");
            if (!raw.matches("\\d+(\\.\\d{1,2})?")) {
                error("Please enter a valid opening deposit.");
                continue;
            }
            double open = Theme.round2(Double.parseDouble(raw));
            String t = (String) type.getSelectedItem();
            if (open > MAX_TX) {
                error("Opening deposit cannot exceed " + Theme.money(MAX_TX) + ".");
                continue;
            }
            if (t.equals("Savings") && open < Account.SAVINGS_MIN) {
                error("Savings accounts need an opening deposit of at least " + Theme.money(Account.SAVINGS_MIN) + ".");
                continue;
            }
            String p1 = new String(pin.getPassword());
            if (!p1.matches("\\d{4}")) {
                error("PIN must be exactly 4 digits.");
                continue;
            }
            if (!p1.equals(new String(pin2.getPassword()))) {
                error("The two PIN entries do not match.");
                continue;
            }

            Account acc = new Account(bank.nextNumber(), nm, t, p1);
            if (open > 0) acc.credit(open, "Opening Balance", "");
            bank.accounts.add(acc);
            current = acc;
            bank.save();
            reloadAccounts();
            refresh();
            toast("Account " + acc.number + " created for " + nm + ".", false);
            return;
        }
    }

    private void showChangePinDialog() {
        JPasswordField old = new JPasswordField(6);
        JPasswordField n1 = new JPasswordField(6);
        JPasswordField n2 = new JPasswordField(6);
        Theme.style(old);
        Theme.style(n1);
        Theme.style(n2);

        JPanel p = Theme.form();
        Theme.row(p, 0, "Current PIN", old);
        Theme.row(p, 1, "New PIN", n1);
        Theme.row(p, 2, "Confirm new PIN", n2);

        while (true) {
            int r = JOptionPane.showConfirmDialog(this, p, "Change PIN \u2014 " + current.number, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            if (!new String(old.getPassword()).equals(current.pin)) {
                error("Current PIN is incorrect.");
                continue;
            }
            String a = new String(n1.getPassword());
            if (!a.matches("\\d{4}")) {
                error("New PIN must be exactly 4 digits.");
                continue;
            }
            if (!a.equals(new String(n2.getPassword()))) {
                error("The new PIN entries do not match.");
                continue;
            }
            current.pin = a;
            bank.save();
            toast("PIN updated for " + current.number + ".", false);
            return;
        }
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(current.number + "_statement.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f.exists()) {
            int r = JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Overwrite?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            w.println("Date,Type,Note,Credit,Debit,Balance After");
            for (int i = 0; i < table.getRowCount(); i++) {
                Transaction t = txModel.get(table.convertRowIndexToModel(i));
                w.println(csv(Theme.DT.format(t.time)) + "," + csv(t.type) + "," + csv(t.note) + ","
                        + (t.credit ? String.format("%.2f", t.amount) : "") + ","
                        + (!t.credit ? String.format("%.2f", t.amount) : "") + ","
                        + String.format("%.2f", t.balanceAfter));
            }
            toast("Statement exported to " + f.getName() + " (" + table.getRowCount() + " rows).", false);
        } catch (IOException ex) {
            toast("Could not export statement: " + ex.getMessage(), true);
        }
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private void resetData() {
        int r = JOptionPane.showConfirmDialog(this,
                "Reset all accounts and transactions back to the sample data?\nThis cannot be undone.",
                "Reset Demo Data", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        bank.accounts.clear();
        bank.accounts.addAll(Bank.seeded().accounts);
        current = bank.accounts.get(0);
        bank.save();
        reloadAccounts();
        refresh();
        toast("Demo data restored.", false);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "BankSim \u2014 Core Banking Simulation\n\n"
                        + "Shortcuts (Ctrl / Cmd +):\n"
                        + "  D  Deposit      W  Withdraw      T  Transfer\n"
                        + "  I  Interest     N  New account   P  Change PIN\n"
                        + "  H  Hide/Show balance      E  Export CSV\n\n"
                        + "Rules:\n"
                        + "  \u2022 Savings accounts keep a minimum balance of " + Theme.money(Account.SAVINGS_MIN) + "\n"
                        + "  \u2022 Daily debit limit: " + Theme.money(Account.DAILY_LIMIT) + " per account\n"
                        + "  \u2022 Withdrawals and transfers need the account PIN\n\n"
                        + "This is a learning simulation, not a real banking system.",
                "About BankSim", JOptionPane.INFORMATION_MESSAGE);
    }

    private void exitApp() {
        bank.save();
        dispose();
        System.exit(0);
    }

    public static void main(String[] args) {
        UIManager.put("OptionPane.background", Color.WHITE);
        UIManager.put("Panel.background", Color.WHITE);
        UIManager.put("OptionPane.messageFont", Theme.font(Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", Theme.font(Font.PLAIN, 13));
        UIManager.put("Label.font", Theme.font(Font.PLAIN, 13));
        SwingUtilities.invokeLater(() -> new BankSim().setVisible(true));
    }
}

// =========================================================================
//  THEME & HELPERS
// =========================================================================

interface DocChange extends DocumentListener {
    void changed();
    @Override default void insertUpdate(DocumentEvent e) { changed(); }
    @Override default void removeUpdate(DocumentEvent e) { changed(); }
    @Override default void changedUpdate(DocumentEvent e) { changed(); }
}

final class Theme {
    static final Color PRIMARY = new Color(0x1565C0);
    static final Color DARK = new Color(0x0D47A1);
    static final Color ACCENT = new Color(0x1E88E5);
    static final Color LIGHT = new Color(0xE3F2FD);
    static final Color SOFT = new Color(0xBBDEFB);
    static final Color BG = new Color(0xF4F8FD);
    static final Color TEXT = new Color(0x15243B);
    static final Color MUTED = new Color(0x6B7C93);
    static final Color LINE = new Color(0xD6E4F5);
    static final Color GRID = new Color(0xE6EEF9);
    static final Color SELECT = new Color(0xD0E6FB);
    static final Color ROW_ALT = new Color(0xF6FAFF);
    static final Color GREEN = new Color(0x2E7D32);
    static final Color RED = new Color(0xC62828);

    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM");
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy  HH:mm:ss");

    private Theme() {}

    static Font font(int style, int size) {
        return new Font("Segoe UI", style, size);
    }

    static JLabel label(String text, int size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    static JButton linkButton(String text) {
        JButton b = new JButton(text);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setForeground(Color.WHITE);
        b.setFont(font(Font.BOLD, 12));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    static JTextField field(int cols) {
        JTextField f = new JTextField(cols);
        style(f);
        return f;
    }

    static void style(JTextField f) {
        f.setFont(font(Font.PLAIN, 13));
        f.setBorder(new CompoundBorder(new LineBorder(new Color(0xA9C8EE), 1, true), new EmptyBorder(6, 10, 6, 10)));
    }

    static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        return p;
    }

    static void row(JPanel p, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        p.add(label(label, 13, Font.BOLD, TEXT), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        p.add(field, c);
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String money(double v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "\u20B9" + nf.format(v);
    }

    static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1e7) return String.format("\u20B9%.1fCr", v / 1e7);
        if (a >= 1e5) return String.format("\u20B9%.1fL", v / 1e5);
        if (a >= 1e3) return String.format("\u20B9%.1fK", v / 1e3);
        return String.format("\u20B9%.0f", v);
    }

    static Color hover(Color c) {
        double lum = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        if (lum > 0.75) {
            return new Color((int) (c.getRed() * 0.93), (int) (c.getGreen() * 0.93), (int) (c.getBlue() * 0.95));
        }
        return new Color(c.getRed() + (255 - c.getRed()) / 7, c.getGreen() + (255 - c.getGreen()) / 7, c.getBlue() + (255 - c.getBlue()) / 7);
    }

    static Color pressed(Color c) {
        return new Color((int) (c.getRed() * 0.82), (int) (c.getGreen() * 0.82), (int) (c.getBlue() * 0.85));
    }
}

class RoundedPanel extends JPanel {
    private final int arc;
    private final Color c1, c2;
    private final boolean shadow;

    RoundedPanel(LayoutManager lm, int arc, Color c1, Color c2, boolean shadow) {
        super(lm);
        this.arc = arc;
        this.c1 = c1;
        this.c2 = c2;
        this.shadow = shadow;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (shadow) {
            g2.setColor(new Color(13, 71, 161, 12));
            g2.fillRoundRect(0, 3, w - 1, h - 3, arc + 2, arc + 2);
            g2.setColor(new Color(13, 71, 161, 20));
            g2.fillRoundRect(1, 2, w - 3, h - 3, arc, arc);
        }
        int bw = shadow ? w - 3 : w, bh = shadow ? h - 4 : h;
        g2.setPaint(c2 == null ? c1 : new GradientPaint(0, 0, c1, w, h, c2));
        g2.fillRoundRect(0, 0, bw, bh, arc, arc);
        g2.dispose();
        super.paintComponent(g);
    }
}

class RoundedButton extends JButton {
    private final Color bg;

    RoundedButton(String text, Color bg, Color fg) {
        super(text);
        this.bg = bg;
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(fg);
        setFont(Theme.font(Font.BOLD, 13));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new EmptyBorder(11, 18, 11, 18));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = !isEnabled() ? new Color(0xE6EBF2)
                : getModel().isPressed() ? Theme.pressed(bg)
                : getModel().isRollover() ? Theme.hover(bg) : bg;
        g2.setColor(c);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        g2.dispose();
        super.paintComponent(g);
    }
}

class MeterBar extends JComponent {
    private double fraction;

    MeterBar() {
        setPreferredSize(new Dimension(100, 8));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        setOpaque(false);
    }

    void setFraction(double f) {
        this.fraction = Math.max(0, Math.min(1, f));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g2.setColor(new Color(255, 255, 255, 60));
        g2.fillRoundRect(0, 0, w, h, h, h);
        if (fraction > 0) {
            g2.setColor(fraction > 0.85 ? new Color(0xFFCA28) : Color.WHITE);
            g2.fillRoundRect(0, 0, Math.max(h, (int) (w * fraction)), h, h, h);
        }
        g2.dispose();
    }
}

// =========================================================================
//  BALANCE TREND CHART
// =========================================================================

class TrendChart extends JPanel {
    private static final int L = 64, R = 18, T = 14, B = 26;
    private List<Transaction> data = new ArrayList<>();
    private int hover = -1;

    TrendChart() {
        setOpaque(false);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int n = data.size();
                if (n < 2) return;
                double step = (getWidth() - L - R) / (double) (n - 1);
                int idx = (int) Math.round((e.getX() - L) / step);
                idx = Math.max(0, Math.min(n - 1, idx));
                if (idx != hover) {
                    hover = idx;
                    repaint();
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }
        });
    }

    void setData(List<Transaction> chronological) {
        int n = chronological.size();
        data = new ArrayList<>(chronological.subList(Math.max(0, n - 15), n));
        hover = -1;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int n = data.size();

        if (n < 2) {
            g2.setFont(Theme.font(Font.PLAIN, 13));
            g2.setColor(Theme.MUTED);
            String s = "Make a few transactions to see your balance trend";
            g2.drawString(s, (w - g2.getFontMetrics().stringWidth(s)) / 2, h / 2);
            g2.dispose();
            return;
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Transaction t : data) {
            min = Math.min(min, t.balanceAfter);
            max = Math.max(max, t.balanceAfter);
        }
        if (max - min < 1) { max += 1; min -= 1; }
        double pad = (max - min) * 0.12;
        max += pad;
        min = Math.max(0, min - pad);

        int plotW = w - L - R, plotH = h - T - B;

        // grid + y labels
        g2.setFont(Theme.font(Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            int y = T + plotH * i / 4;
            g2.setColor(Theme.GRID);
            g2.drawLine(L, y, L + plotW, y);
            String lbl = Theme.compact(max - (max - min) * i / 4);
            g2.setColor(Theme.MUTED);
            g2.drawString(lbl, L - 8 - fm.stringWidth(lbl), y + 4);
        }

        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = L + (int) Math.round(plotW * i / (double) (n - 1));
            ys[i] = T + (int) Math.round(plotH * (max - data.get(i).balanceAfter) / (max - min));
        }

        // area fill
        Path2D.Double area = new Path2D.Double();
        area.moveTo(xs[0], T + plotH);
        for (int i = 0; i < n; i++) area.lineTo(xs[i], ys[i]);
        area.lineTo(xs[n - 1], T + plotH);
        area.closePath();
        g2.setPaint(new GradientPaint(0, T, new Color(21, 101, 192, 90), 0, T + plotH, new Color(21, 101, 192, 5)));
        g2.fill(area);

        // line
        Path2D.Double line = new Path2D.Double();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) line.lineTo(xs[i], ys[i]);
        g2.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(Theme.PRIMARY);
        g2.draw(line);

        // dots
        g2.setStroke(new BasicStroke(2f));
        for (int i = 0; i < n; i++) {
            g2.setColor(Color.WHITE);
            g2.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
            g2.setColor(Theme.PRIMARY);
            g2.drawOval(xs[i] - 4, ys[i] - 4, 8, 8);
        }

        // x labels
        g2.setColor(Theme.MUTED);
        g2.setFont(Theme.font(Font.PLAIN, 11));
        String first = data.get(0).time.format(Theme.DAY);
        String last = data.get(n - 1).time.format(Theme.DAY);
        g2.drawString(first, L, h - 8);
        g2.drawString(last, L + plotW - g2.getFontMetrics().stringWidth(last), h - 8);

        // hover tooltip
        if (hover >= 0 && hover < n) {
            Transaction t = data.get(hover);
            g2.setColor(new Color(21, 101, 192, 70));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g2.drawLine(xs[hover], T, xs[hover], T + plotH);

            g2.setStroke(new BasicStroke(2f));
            g2.setColor(Theme.PRIMARY);
            g2.fillOval(xs[hover] - 6, ys[hover] - 6, 12, 12);
            g2.setColor(Color.WHITE);
            g2.fillOval(xs[hover] - 3, ys[hover] - 3, 6, 6);

            String l1 = Theme.money(t.balanceAfter);
            String l2 = t.type + " \u00B7 " + t.time.format(Theme.DT);
            Font f1 = Theme.font(Font.BOLD, 12), f2 = Theme.font(Font.PLAIN, 11);
            int bw = Math.max(g2.getFontMetrics(f1).stringWidth(l1), g2.getFontMetrics(f2).stringWidth(l2)) + 22;
            int bh = 44;
            int bx = Math.max(4, Math.min(w - bw - 4, xs[hover] - bw / 2));
            int by = ys[hover] - bh - 12;
            if (by < 2) by = ys[hover] + 14;
            g2.setColor(Theme.DARK);
            g2.fillRoundRect(bx, by, bw, bh, 10, 10);
            g2.setColor(Color.WHITE);
            g2.setFont(f1);
            g2.drawString(l1, bx + 11, by + 18);
            g2.setColor(Theme.SOFT);
            g2.setFont(f2);
            g2.drawString(l2, bx + 11, by + 35);
        }
        g2.dispose();
    }
}

// =========================================================================
//  TABLE MODEL & RENDERER
// =========================================================================

class TxModel extends AbstractTableModel {
    private List<Transaction> rows = new ArrayList<>();
    private final String[] cols = {"Date & Time", "Description", "Amount", "Balance After"};

    void setData(List<Transaction> chronological) {
        rows = new ArrayList<>(chronological);
        Collections.reverse(rows); // newest first
        fireTableDataChanged();
    }

    Transaction get(int i) { return rows.get(i); }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return cols.length; }
    @Override public String getColumnName(int c) { return cols[c]; }

    @Override
    public Class<?> getColumnClass(int c) {
        switch (c) {
            case 0: return LocalDateTime.class;
            case 1: return String.class;
            default: return Double.class;
        }
    }

    @Override
    public Object getValueAt(int r, int c) {
        Transaction t = rows.get(r);
        switch (c) {
            case 0: return t.time;
            case 1: return t.note.isEmpty() ? t.type : t.type + " \u2014 " + t.note;
            case 2: return t.credit ? t.amount : -t.amount;
            default: return t.balanceAfter;
        }
    }
}

class LedgerRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
        super.getTableCellRendererComponent(table, value, sel, false, row, col);
        TxModel m = (TxModel) table.getModel();
        Transaction t = m.get(table.convertRowIndexToModel(row));

        setBorder(new EmptyBorder(0, 12, 0, 12));
        setFont(Theme.font(Font.PLAIN, 13));
        setBackground(sel ? Theme.SELECT : (row % 2 == 0 ? Color.WHITE : Theme.ROW_ALT));
        setForeground(Theme.TEXT);
        setHorizontalAlignment(SwingConstants.LEFT);

        switch (col) {
            case 0:
                setText(Theme.DT.format(t.time));
                setForeground(Theme.MUTED);
                break;
            case 1:
                setText(String.valueOf(value));
                break;
            case 2:
                setText((t.credit ? "+ " : "- ") + Theme.money(t.amount));
                setForeground(t.credit ? Theme.GREEN : Theme.RED);
                setFont(Theme.font(Font.BOLD, 13));
                setHorizontalAlignment(SwingConstants.RIGHT);
                break;
            default:
                setText(Theme.money(t.balanceAfter));
                setHorizontalAlignment(SwingConstants.RIGHT);
        }
        return this;
    }
}

// =========================================================================
//  MODEL & PERSISTENCE
// =========================================================================

class Transaction implements Serializable {
    private static final long serialVersionUID = 1L;
    final LocalDateTime time;
    final String type;
    final String note;
    final double amount;
    final boolean credit;
    final double balanceAfter;

    Transaction(LocalDateTime time, String type, String note, double amount, boolean credit, double balanceAfter) {
        this.time = time;
        this.type = type;
        this.note = note == null ? "" : note;
        this.amount = amount;
        this.credit = credit;
        this.balanceAfter = balanceAfter;
    }
}

class Account implements Serializable {
    private static final long serialVersionUID = 1L;
    static final double DAILY_LIMIT = 50_000;
    static final double SAVINGS_MIN = 500;
    static final double INTEREST_RATE = 0.035;

    final String number;
    final String holder;
    final String type; // "Savings" or "Current"
    String pin;
    double balance;
    final List<Transaction> txs = new ArrayList<>();

    Account(String number, String holder, String type, String pin) {
        this.number = number;
        this.holder = holder;
        this.type = type;
        this.pin = pin;
    }

    double minBalance() {
        return type.equals("Savings") ? SAVINGS_MIN : 0;
    }

    double todaysDebits() {
        LocalDate today = LocalDate.now();
        double sum = 0;
        for (Transaction t : txs) {
            if (!t.credit && t.time.toLocalDate().equals(today)) sum += t.amount;
        }
        return sum;
    }

    /** Returns an error message if the debit is not allowed, otherwise null. */
    String checkDebit(double amt) {
        if (amt > balance) {
            return "Insufficient balance. Available: " + Theme.money(balance);
        }
        if (balance - amt < minBalance()) {
            return "This would take the balance below the minimum of " + Theme.money(minBalance())
                    + " required for Savings accounts.";
        }
        if (todaysDebits() + amt > DAILY_LIMIT) {
            double left = Math.max(0, DAILY_LIMIT - todaysDebits());
            return "Daily limit of " + Theme.money(DAILY_LIMIT) + " exceeded. Remaining today: " + Theme.money(left);
        }
        return null;
    }

    void credit(double amt, String type, String note) {
        post(LocalDateTime.now(), true, amt, type, note);
    }

    void debit(double amt, String type, String note) {
        post(LocalDateTime.now(), false, amt, type, note);
    }

    void post(LocalDateTime when, boolean credit, double amt, String type, String note) {
        balance = Theme.round2(credit ? balance + amt : balance - amt);
        txs.add(new Transaction(when, type, note, amt, credit, balance));
    }
}

class Bank implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final File FILE = new File("banksim_data.ser");

    final List<Account> accounts = new ArrayList<>();

    static Bank load() {
        if (FILE.exists()) {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(FILE))) {
                Bank b = (Bank) in.readObject();
                if (!b.accounts.isEmpty()) return b;
            } catch (Exception ignored) {
                // corrupted or incompatible file: fall back to sample data
            }
        }
        return seeded();
    }

    void save() {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(FILE))) {
            out.writeObject(this);
        } catch (IOException ignored) {
        }
    }

    Account find(String number) {
        for (Account a : accounts) {
            if (a.number.equalsIgnoreCase(number)) return a;
        }
        return null;
    }

    String nextNumber() {
        int n = 1001;
        while (find("ACC" + n) != null) n++;
        return "ACC" + n;
    }

    static Bank seeded() {
        Bank b = new Bank();
        LocalDateTime now = LocalDateTime.now();

        Account a = new Account("ACC1001", "Aravind Kumar", "Savings", "1234");
        a.post(now.minusDays(30), true, 10000, "Opening Balance", "");
        a.post(now.minusDays(24), true, 4500, "Deposit", "Stipend");
        a.post(now.minusDays(18), false, 1200, "Withdrawal", "ATM cash");
        a.post(now.minusDays(12), true, 3000, "Deposit", "Freelance project");
        a.post(now.minusDays(7), false, 2800, "Withdrawal", "Books & course fees");
        a.post(now.minusDays(3), true, 1500, "Deposit", "Cash gift");
        b.accounts.add(a);

        Account r = new Account("ACC1002", "Rohith G.", "Savings", "1234");
        r.post(now.minusDays(28), true, 6000, "Opening Balance", "");
        r.post(now.minusDays(15), true, 3000, "Deposit", "Salary advance");
        r.post(now.minusDays(5), false, 499.50, "Withdrawal", "Groceries");
        b.accounts.add(r);

        Account k = new Account("ACC1003", "Karna N.", "Current", "1234");
        k.post(now.minusDays(26), true, 20000, "Opening Balance", "");
        k.post(now.minusDays(14), true, 5000, "Deposit", "Client payment");
        k.post(now.minusDays(4), false, 2600, "Withdrawal", "Office supplies");
        b.accounts.add(k);

        return b;
    }
}