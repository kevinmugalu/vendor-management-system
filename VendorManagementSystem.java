import java.awt.*;
import java.awt.event.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.*;



class Vendor {
    private final String id;
    private final String name;
    private final String market;
    private final String shift;
    private boolean approved;
    private String stallId;
    private final LocalDateTime registeredAt;

    public Vendor(String id, String name, String market, String shift) {
        this.id           = Objects.requireNonNull(id,     "id must not be null");
        this.name         = Objects.requireNonNull(name,   "name must not be null");
        this.market       = Objects.requireNonNull(market, "market must not be null");
        this.shift        = Objects.requireNonNull(shift,  "shift must not be null");
        this.approved     = false;
        this.stallId      = null;
        this.registeredAt = LocalDateTime.now();
    }

    // Getters
    public String getId()           { return id; }
    public String getName()         { return name; }
    public String getMarket()       { return market; }
    public String getShift()        { return shift; }
    public boolean isApproved()     { return approved; }
    public String getStallId()      { return stallId; }
    public boolean isAllocated()    { return stallId != null; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }

    // Setters
    public void approve()               { this.approved = true; }
    public void assignStall(String sid) { this.stallId  = sid; }
    public void revokeStall()           { this.stallId  = null; }

    @Override
    public String toString() {
        return String.format("Vendor[id=%s, name=%s, market=%s, approved=%b]",
                id, name, market, approved);
    }
}



class Stall {
    private final String id;
    private final String market;
    private String vendorId;

    public Stall(String id, String market) {
        this.id     = Objects.requireNonNull(id,     "stall id must not be null");
        this.market = Objects.requireNonNull(market, "market must not be null");
    }

    public String  getId()        { return id; }
    public String  getMarket()    { return market; }
    public boolean isAvailable()  { return vendorId == null; }
    public String  getVendorId()  { return vendorId; }

    public void assign(String vid)  { this.vendorId = vid; }
    public void vacate()            { this.vendorId = null; }
}

// ------------------------------------------------------------


class AuditEntry {
    private final String actor;
    private final String action;
    private final LocalDateTime timestamp;

    public AuditEntry(String actor, String action) {
        this.actor     = actor;
        this.action    = action;
        this.timestamp = LocalDateTime.now();
    }

    public String getActor()     { return actor; }
    public String getAction()    { return action; }
    public String getTimestamp() {
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}


enum Role { ADMIN, MANAGER, VENDOR }

enum Market {
    USAFI("Usafi Market", "USA"),
    BUSEGA("Busega Market", "BUS"),
    OWINO("Owino Market", "OWI");

    private final String displayName;
    private final String prefix;

    Market(String displayName, String prefix) {
        this.displayName = displayName;
        this.prefix      = prefix;
    }

    public String getDisplayName() { return displayName; }
    public String getPrefix()      { return prefix; }

    public static String[] displayNames() {
        return Arrays.stream(values())
                     .map(Market::getDisplayName)
                     .toArray(String[]::new);
    }

    public static Market fromDisplayName(String name) {
        return Arrays.stream(values())
                     .filter(m -> m.displayName.equalsIgnoreCase(name))
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("Unknown market: " + name));
    }
}

enum Shift { DAY, NIGHT }



class VendorNotFoundException extends RuntimeException {
    public VendorNotFoundException(String id) {
        super("Vendor not found: " + id);
    }
}

class StallUnavailableException extends RuntimeException {
    public StallUnavailableException(String market) {
        super("No available stall in " + market);
    }
}

class VendorNotApprovedException extends RuntimeException {
    public VendorNotApprovedException(String id) {
        super("Vendor " + id + " is not approved yet.");
    }
}

// SERVICE / CONTROLLER

class VendorService {

    private final List<Vendor>     vendors    = new ArrayList<>();
    private final List<Stall>      stalls     = new ArrayList<>();
    private final List<AuditEntry> auditLog   = new ArrayList<>();
    private int idCounter = 1001;

    public VendorService() {
        // Seed stalls for each market
        for (Market m : Market.values()) {
            for (int i = 1; i <= 5; i++) {
                stalls.add(new Stall(m.getPrefix() + "-" + (100 + i), m.getDisplayName()));
            }
        }
    }

    //  Vendor operations 

    public Vendor register(String actor, String name, String market, String shift) {
        validateNotBlank(name,   "Vendor name");
        validateNotBlank(market, "Market");
        validateNotBlank(shift,  "Shift");

        String id     = "V" + idCounter++;
        Vendor vendor = new Vendor(id, name, market, shift);
        vendors.add(vendor);
        audit(actor, "Registered vendor: " + id + " (" + name + ")");
        return vendor;
    }

    public void approve(String actor, String vendorId) {
        Vendor v = findVendorById(vendorId); // throws if not found
        v.approve();
        audit(actor, "Approved vendor: " + vendorId);
    }

    public void allocate(String actor, String vendorId) {
        Vendor v = findVendorById(vendorId);
        if (!v.isApproved())   throw new VendorNotApprovedException(vendorId);
        if (v.isAllocated())   throw new IllegalStateException("Vendor " + vendorId + " already has stall " + v.getStallId());

        Stall stall = stalls.stream()
                            .filter(s -> s.isAvailable() &&
                                         s.getMarket().equalsIgnoreCase(v.getMarket()))
                            .findFirst()
                            .orElseThrow(() -> new StallUnavailableException(v.getMarket()));

        stall.assign(vendorId);
        v.assignStall(stall.getId());
        audit(actor, "Allocated stall " + stall.getId() + " to vendor " + vendorId);
    }

    public void deallocate(String actor, String vendorId) {
        Vendor v = findVendorById(vendorId);
        if (!v.isAllocated()) throw new IllegalStateException("Vendor " + vendorId + " has no stall assigned.");

        stalls.stream()
              .filter(s -> v.getStallId().equals(s.getId()))
              .findFirst()
              .ifPresent(Stall::vacate);

        String oldStall = v.getStallId();
        v.revokeStall();
        audit(actor, "Deallocated stall " + oldStall + " from vendor " + vendorId);
    }

    //  Queries 

    public List<Vendor> getAllVendors() { return Collections.unmodifiableList(vendors); }
    public List<Stall>  getAllStalls()  { return Collections.unmodifiableList(stalls); }
    public List<AuditEntry> getAuditLog() { return Collections.unmodifiableList(auditLog); }

    public List<Vendor> getVendorsByMarket(String market) {
        return vendors.stream()
                      .filter(v -> v.getMarket().equalsIgnoreCase(market))
                      .collect(Collectors.toList());
    }

    public List<Stall> getStallsByMarket(String market) {
        return stalls.stream()
                     .filter(s -> s.getMarket().equalsIgnoreCase(market))
                     .collect(Collectors.toList());
    }

    public Optional<Vendor> findVendor(String id) {
        return vendors.stream().filter(v -> v.getId().equals(id)).findFirst();
    }

    //  Helpers 

    private Vendor findVendorById(String id) {
        return findVendor(id).orElseThrow(() -> new VendorNotFoundException(id));
    }

    private void audit(String actor, String action) {
        auditLog.add(new AuditEntry(actor, action));
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(fieldName + " must not be blank.");
    }
}


// AUTH SERVICE


class AuthService {
    
    private final Map<String, String> credentials = new HashMap<>();
    private final Map<String, Role>   userRoles   = new HashMap<>();

    public AuthService() {
        // Seed default accounts
        addUser("admin", "admin123", Role.ADMIN);
        addUser("manager", "manager123", Role.MANAGER);
        addUser("vendor", "vendor123", Role.VENDOR);
    }

    public boolean addUser(String username, String password, Role role) {
        if (username == null || username.isBlank())  return false;
        if (password == null || password.length() < 4) return false;
        if (credentials.containsKey(username))       return false;  // duplicate

        credentials.put(username, password); // hash in production
        userRoles.put(username, role);
        return true;
    }

    /** Returns the Role on success, or empty on failure. */
    public Optional<Role> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        if (password.equals(credentials.get(username)))
            return Optional.of(userRoles.get(username));
        return Optional.empty();
    }

    public boolean userExists(String username) {
        return credentials.containsKey(username);
    }
}


// UI themeing

class Theme {
    static final Color PRIMARY      = new Color(13,  71, 161);   // deep blue
    static final Color PRIMARY_DARK = new Color( 8,  50, 120);
    static final Color ACCENT       = new Color(255, 152,   0);   // amber
    static final Color SUCCESS      = new Color( 56, 142,  60);
    static final Color DANGER       = new Color(198,  40,  40);
    static final Color BG           = new Color(236, 239, 241);
    static final Color CARD         = Color.WHITE;
    static final Color TEXT_MUTED   = new Color(96, 125, 139);
    static final Color TEXT         = new Color(33,  33,  33);
    static final Color HEADER_FG    = Color.WHITE;

    static final Font TITLE   = new Font("Segoe UI", Font.BOLD,  20);
    static final Font HEADING = new Font("Segoe UI", Font.BOLD,  14);
    static final Font BODY    = new Font("Segoe UI", Font.PLAIN, 13);
    static final Font SMALL   = new Font("Segoe UI", Font.PLAIN, 11);
    static final Font BTN     = new Font("Segoe UI", Font.BOLD,  12);

    private Theme() {}
}

class UiFactory {

    private UiFactory() {}

    static JButton primaryButton(String label) {
        JButton btn = new JButton(label);
        styleButton(btn, Theme.PRIMARY, Theme.HEADER_FG);
        return btn;
    }

    static JButton dangerButton(String label) {
        JButton btn = new JButton(label);
        styleButton(btn, Theme.DANGER, Theme.HEADER_FG);
        return btn;
    }

    static JButton successButton(String label) {
        JButton btn = new JButton(label);
        styleButton(btn, Theme.SUCCESS, Theme.HEADER_FG);
        return btn;
    }

    static JButton accentButton(String label) {
        JButton btn = new JButton(label);
        styleButton(btn, Theme.ACCENT, Theme.TEXT);
        return btn;
    }

    private static void styleButton(JButton btn, Color bg, Color fg) {
        btn.setFont(Theme.BTN);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        // Hover effect
        Color hovered = bg.darker();
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(hovered); }
            @Override public void mouseExited(MouseEvent e)  { btn.setBackground(bg); }
        });
    }

    static JTextField textField() {
        JTextField tf = new JTextField();
        tf.setFont(Theme.BODY);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 180)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        return tf;
    }

    static JPasswordField passwordField() {
        JPasswordField pf = new JPasswordField();
        pf.setFont(Theme.BODY);
        pf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(180, 180, 180)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        return pf;
    }

    static JLabel label(String text, Font font, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(font);
        lbl.setForeground(color);
        return lbl;
    }

    static JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(Theme.CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(210, 210, 210)),
            BorderFactory.createEmptyBorder(20, 24, 20, 24)
        ));
        return panel;
    }

    static JTable styledTable(DefaultTableModel model) {
        JTable table = new JTable(model) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table.setFont(Theme.BODY);
        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(new Color(187, 222, 251));
        table.setSelectionForeground(Theme.TEXT);
        table.getTableHeader().setFont(Theme.HEADING);
        table.getTableHeader().setBackground(Theme.PRIMARY);
        table.getTableHeader().setForeground(Theme.HEADER_FG);
        table.getTableHeader().setBorder(BorderFactory.createEmptyBorder());

        // Alternating row colours
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable tbl, Object val, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, sel, foc, row, col);
                if (!sel) c.setBackground(row % 2 == 0 ? Color.WHITE : new Color(245, 249, 255));
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return c;
            }
        });
        return table;
    }

    static void showError(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    static void showSuccess(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Success", JOptionPane.INFORMATION_MESSAGE);
    }
}


// PANELS


//login panel
class LoginPanel extends JPanel {

    interface LoginListener {
        void onLogin(String username, String password);
        void onCreateAccount();
    }

    private final JTextField     usernameField;
    private final JPasswordField passwordField;

    LoginPanel(LoginListener listener) {
        setLayout(new GridBagLayout());
        setBackground(Theme.BG);

        JPanel card = UiFactory.card();
        card.setLayout(new GridBagLayout());
        card.setPreferredSize(new Dimension(380, 320));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets    = new Insets(8, 8, 8, 8);
        gc.fill      = GridBagConstraints.HORIZONTAL;
        gc.weightx   = 1;

        // Title
        JLabel title = UiFactory.label("KCCA Vendor System", Theme.TITLE, Theme.PRIMARY);
        JLabel sub   = UiFactory.label("Kampala Capital City Authority", Theme.SMALL, Theme.TEXT_MUTED);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.anchor = GridBagConstraints.CENTER;
        card.add(title, gc);
        gc.gridy = 1;
        card.add(sub, gc);

        // Separator
        gc.gridy = 2;
        card.add(new JSeparator(), gc);

        // Fields
        usernameField = UiFactory.textField();
        usernameField.setColumns(20);
        passwordField = UiFactory.passwordField();

        gc.gridwidth = 1; gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 3; card.add(UiFactory.label("Username", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(usernameField, gc);

        gc.gridx = 0; gc.gridy = 4; card.add(UiFactory.label("Password", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(passwordField, gc);

        // Buttons
        JButton loginBtn  = UiFactory.primaryButton("Login");
        JButton createBtn = UiFactory.accentButton("Create Account");

        gc.gridx = 0; gc.gridy = 5; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL;
        card.add(loginBtn,  gc);
        gc.gridy = 6;
        card.add(createBtn, gc);

        add(card);

        // Enter key on password → login
        passwordField.addActionListener(e -> triggerLogin(listener));
        loginBtn.addActionListener(e      -> triggerLogin(listener));
        createBtn.addActionListener(e     -> listener.onCreateAccount());
    }

    private void triggerLogin(LoginListener listener) {
        listener.onLogin(usernameField.getText().trim(),
                         new String(passwordField.getPassword()));
    }
}


/**
 * Account creation panel.
 */
class RegisterPanel extends JPanel {

    interface RegisterListener {
        /** @return error message, or null on success */
        String onRegister(String username, String password, Role role);
        void   onBack();
    }

    RegisterPanel(RegisterListener listener) {
        setLayout(new GridBagLayout());
        setBackground(Theme.BG);

        JPanel card = UiFactory.card();
        card.setLayout(new GridBagLayout());
        card.setPreferredSize(new Dimension(380, 310));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(8, 8, 8, 8);
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        JLabel title = UiFactory.label("Create Account", Theme.TITLE, Theme.PRIMARY);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.anchor = GridBagConstraints.CENTER;
        card.add(title, gc);
        gc.gridy = 1;
        card.add(new JSeparator(), gc);

        JTextField     usernameField = UiFactory.textField();
        JPasswordField passwordField = UiFactory.passwordField();
        JComboBox<Role> roleBox      = new JComboBox<>(new Role[]{Role.VENDOR, Role.MANAGER});
        roleBox.setFont(Theme.BODY);

        gc.gridwidth = 1; gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 2; card.add(UiFactory.label("Username", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(usernameField, gc);

        gc.gridx = 0; gc.gridy = 3; card.add(UiFactory.label("Password (min 4)", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(passwordField, gc);

        gc.gridx = 0; gc.gridy = 4; card.add(UiFactory.label("Role", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(roleBox, gc);

        JButton createBtn = UiFactory.successButton("Create Account");
        JButton backBtn   = UiFactory.primaryButton("← Back");

        gc.gridx = 0; gc.gridy = 5; gc.gridwidth = 2; gc.fill = GridBagConstraints.HORIZONTAL;
        card.add(createBtn, gc);
        gc.gridy = 6;
        card.add(backBtn, gc);

        add(card);

        createBtn.addActionListener(e -> {
            String err = listener.onRegister(
                    usernameField.getText().trim(),
                    new String(passwordField.getPassword()),
                    (Role) roleBox.getSelectedItem());
            if (err != null) UiFactory.showError(this, err);
            else {
                UiFactory.showSuccess(this, "Account created successfully!");
                listener.onBack();
            }
        });

        backBtn.addActionListener(e -> listener.onBack());
    }
}


// MAIN APPLICATION system


public class VendorManagementSystem extends JFrame {

    private final AuthService   authService   = new AuthService();
    private final VendorService vendorService = new VendorService();

    private String currentUser;
    private Role   currentRole;

    // Vendor-role state
    private String sessionVendorId;

    public VendorManagementSystem() {
        applySystemLookAndFeel();

        setTitle("KCCA Vendor Management System");
        setSize(1100, 680);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        showLogin();
    }

    
    // SCREEN TRANSITIONS
    private void swap1(JPanel newPanel) {
        setContentPane(newPanel);
        revalidate();
        repaint();
    }

    private void showLogin() {
        sessionVendorId = null;
        currentUser     = null;
        currentRole     = null;

        LoginPanel panel = new LoginPanel(new LoginPanel.LoginListener() {
            @Override
            public void onLogin(String username, String password) {
                if (username.isBlank()) {
                    UiFactory.showError(VendorManagementSystem.this, "Username is required.");
                    return;
                }
                authService.authenticate(username, password)
                    .ifPresentOrElse(
                        role -> {
                            currentUser = username;
                            currentRole = role;
                            showDashboard();
                        },
                        () -> UiFactory.showError(
                                VendorManagementSystem.this,
                                "Invalid username or password.")
                    );
            }
            @Override
            public void onCreateAccount() { showRegister(); }
        });

        swap1(panel);
    }

    private void showRegister() {
        RegisterPanel panel = new RegisterPanel(new RegisterPanel.RegisterListener() {
            @Override
            public String onRegister(String username, String password, Role role) {
                if (username.isBlank())      return "Username is required.";
                if (password.length() < 4)   return "Password must be at least 4 characters.";
                if (authService.userExists(username)) return "Username already taken.";
                authService.addUser(username, password, role);
                return null; // success
            }
            @Override public void onBack() { showLogin(); }
        });

        swap1(panel);
    }

    private void showDashboard() {
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Theme.BG);

        // ---- Header ----
        JPanel header = buildHeader();
        main.add(header, BorderLayout.NORTH);

        // ---- Tab pane ----
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.HEADING);
        tabs.setBackground(Theme.BG);

        switch (currentRole) {
            case ADMIN   -> buildAdminTabs(tabs);
            case MANAGER -> buildManagerTabs(tabs);
            case VENDOR  -> buildVendorTabs(tabs);
        }

        main.add(tabs, BorderLayout.CENTER);
        swap1(main);
    }

    
    // HEADER
    

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PRIMARY_DARK);
        header.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JLabel titleLbl = UiFactory.label(
                "KCCA Vendor Management System", Theme.TITLE, Theme.HEADER_FG);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);

        JLabel userLbl = UiFactory.label(
                currentRole + "  |  " + currentUser, Theme.BODY, new Color(200, 220, 255));
        JButton logoutBtn = UiFactory.dangerButton("Logout");
        logoutBtn.addActionListener(e -> showLogin());

        right.add(userLbl);
        right.add(logoutBtn);

        header.add(titleLbl, BorderLayout.WEST);
        header.add(right,    BorderLayout.EAST);
        return header;
    }

    
    // ADMIN TABS
    

    private void buildAdminTabs(JTabbedPane tabs) {
        tabs.addTab("Vendors",      buildVendorTableTab());
        tabs.addTab("Stalls",       buildStallTableTab());
        tabs.addTab("Approve",      buildApproveTab());
        tabs.addTab("Allocate",     buildAllocateTab());
        tabs.addTab("Deallocate",   buildDeallocateTab());
        tabs.addTab("Audit Log",    buildAuditTab());
    }

    
    // MANAGER TABS
    

    private void buildManagerTabs(JTabbedPane tabs) {
        // Managers see their market only – for demo we show all
        tabs.addTab("Vendors",   buildVendorTableTab());
        tabs.addTab("Stalls",    buildStallTableTab());
        tabs.addTab("Audit Log", buildAuditTab());
    }

    
    // VENDOR TABS
    

    private void buildVendorTabs(JTabbedPane tabs) {
        tabs.addTab("Register",     buildVendorSelfRegisterTab());
        tabs.addTab("My Status",    buildVendorStatusTab());
    }

    
    // TAB BUILDERS
    

    /** Live vendor table with a refresh button. */
    private JPanel buildVendorTableTab() {
        DefaultTableModel model = new DefaultTableModel();
        JTable table = UiFactory.styledTable(model);
        model.setColumnIdentifiers(
                new String[]{"ID", "Name", "Market", "Shift", "Approved", "Stall ID", "Registered"});

        JButton refresh = UiFactory.primaryButton("⟳ Refresh");
        Runnable load = () -> {
            model.setRowCount(0);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            for (Vendor v : vendorService.getAllVendors()) {
                model.addRow(new Object[]{
                    v.getId(), v.getName(), v.getMarket(), v.getShift(),
                    v.isApproved() ? "✓ Yes" : "✗ No",
                    v.getStallId() != null ? v.getStallId() : "—",
                    v.getRegisteredAt().format(fmt)
                });
            }
        };
        load.run();
        refresh.addActionListener(e -> load.run());

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        top.add(UiFactory.label("All Vendors", Theme.HEADING, Theme.PRIMARY));
        top.add(refresh);

        panel.add(top,                    BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStallTableTab() {
        DefaultTableModel model = new DefaultTableModel();
        JTable table = UiFactory.styledTable(model);
        model.setColumnIdentifiers(new String[]{"Stall ID", "Market", "Status", "Assigned Vendor"});

        JButton refresh = UiFactory.primaryButton("⟳ Refresh");
        Runnable load = () -> {
            model.setRowCount(0);
            for (Stall s : vendorService.getAllStalls()) {
                model.addRow(new Object[]{
                    s.getId(), s.getMarket(),
                    s.isAvailable() ? "Available" : "Occupied",
                    s.getVendorId() != null ? s.getVendorId() : "—"
                });
            }
        };
        load.run();
        refresh.addActionListener(e -> load.run());

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        top.add(UiFactory.label("All Stalls", Theme.HEADING, Theme.PRIMARY));
        top.add(refresh);

        panel.add(top,                    BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildApproveTab() {
        return buildActionTab(
            "Approve Vendor",
            "Enter the Vendor ID to approve:",
            UiFactory.successButton("✔ Approve"),
            vendorId -> vendorService.approve(currentUser, vendorId));
    }

    private JPanel buildAllocateTab() {
        return buildActionTab(
            "Allocate Stall",
            "Enter the Vendor ID to allocate a stall:",
            UiFactory.primaryButton("Allocate Stall"),
            vendorId -> vendorService.allocate(currentUser, vendorId));
    }

    private JPanel buildDeallocateTab() {
        return buildActionTab(
            "Deallocate Stall",
            "Enter the Vendor ID to remove from stall:",
            UiFactory.dangerButton("✘ Deallocate"),
            vendorId -> vendorService.deallocate(currentUser, vendorId));
    }

    /** Generic single-field action tab */
    private JPanel buildActionTab(String title, String prompt,
                                  JButton actionBtn, ActionCallback callback) {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(Theme.BG);

        JPanel card = UiFactory.card();
        card.setLayout(new GridBagLayout());
        card.setPreferredSize(new Dimension(420, 200));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.fill   = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        JLabel titleLbl  = UiFactory.label(title, Theme.HEADING, Theme.PRIMARY);
        JLabel promptLbl = UiFactory.label(prompt, Theme.BODY, Theme.TEXT);
        JTextField idField = UiFactory.textField();

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; card.add(titleLbl, gc);
        gc.gridy = 1; card.add(new JSeparator(), gc);
        gc.gridy = 2; card.add(promptLbl, gc);
        gc.gridy = 3; card.add(idField,   gc);
        gc.gridy = 4; card.add(actionBtn, gc);

        actionBtn.addActionListener(e -> {
            String id = idField.getText().trim();
            if (id.isBlank()) {
                UiFactory.showError(this, "Vendor ID is required.");
                return;
            }
            try {
                callback.execute(id);
                UiFactory.showSuccess(this, title + " completed for: " + id);
                idField.setText("");
            } catch (Exception ex) {
                UiFactory.showError(this, ex.getMessage());
            }
        });

        outer.add(card);
        return outer;
    }

    private JPanel buildAuditTab() {
        DefaultTableModel model = new DefaultTableModel();
        JTable table = UiFactory.styledTable(model);
        model.setColumnIdentifiers(new String[]{"Timestamp", "Actor", "Action"});

        JButton refresh = UiFactory.primaryButton("⟳ Refresh");
        Runnable load = () -> {
            model.setRowCount(0);
            List<AuditEntry> log = vendorService.getAuditLog();
            for (int i = log.size() - 1; i >= 0; i--) {
                AuditEntry e = log.get(i);
                model.addRow(new Object[]{e.getTimestamp(), e.getActor(), e.getAction()});
            }
        };
        load.run();
        refresh.addActionListener(e -> load.run());

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setOpaque(false);
        top.add(UiFactory.label("Audit Log", Theme.HEADING, Theme.PRIMARY));
        top.add(refresh);

        panel.add(top,                    BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildVendorSelfRegisterTab() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(Theme.BG);

        JPanel card = UiFactory.card();
        card.setLayout(new GridBagLayout());
        card.setPreferredSize(new Dimension(440, 280));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(8, 8, 8, 8);
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        JTextField nameField    = UiFactory.textField();
        JComboBox<String> marketBox = new JComboBox<>(Market.displayNames());
        JComboBox<Shift>  shiftBox  = new JComboBox<>(Shift.values());
        marketBox.setFont(Theme.BODY);
        shiftBox.setFont(Theme.BODY);

        JButton registerBtn = UiFactory.primaryButton("Register as Vendor");

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        card.add(UiFactory.label("Vendor Registration", Theme.HEADING, Theme.PRIMARY), gc);
        gc.gridy = 1; card.add(new JSeparator(), gc);

        gc.gridwidth = 1; gc.gridx = 0;
        gc.gridy = 2; card.add(UiFactory.label("Full Name", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(nameField, gc);

        gc.gridx = 0; gc.gridy = 3; card.add(UiFactory.label("Market", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(marketBox, gc);

        gc.gridx = 0; gc.gridy = 4; card.add(UiFactory.label("Shift", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(shiftBox, gc);

        gc.gridx = 0; gc.gridy = 5; gc.gridwidth = 2;
        card.add(registerBtn, gc);

        registerBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isBlank()) { UiFactory.showError(this, "Full name is required."); return; }

            String market = marketBox.getSelectedItem().toString();
            String shift  = shiftBox.getSelectedItem().toString();
            Vendor v      = vendorService.register(currentUser, name, market, shift);
            sessionVendorId = v.getId();
            UiFactory.showSuccess(this,
                    "Registered successfully!\nYour Vendor ID is: " + v.getId() +
                    "\nPlease note it for approval & stall requests.");
            nameField.setText("");
        });

        outer.add(card);
        return outer;
    }

    private JPanel buildVendorStatusTab() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(Theme.BG);

        JPanel card = UiFactory.card();
        card.setLayout(new GridBagLayout());
        card.setPreferredSize(new Dimension(440, 200));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(8, 8, 8, 8);
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        JTextField idField = UiFactory.textField();
        if (sessionVendorId != null) idField.setText(sessionVendorId);

        JLabel statusLbl = UiFactory.label("", Theme.BODY, Theme.SUCCESS);
        JButton checkBtn = UiFactory.primaryButton("Check Status");

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        card.add(UiFactory.label("My Vendor Status", Theme.HEADING, Theme.PRIMARY), gc);
        gc.gridy = 1; card.add(new JSeparator(), gc);

        gc.gridwidth = 1; gc.gridx = 0;
        gc.gridy = 2; card.add(UiFactory.label("Vendor ID", Theme.BODY, Theme.TEXT), gc);
        gc.gridx = 1; card.add(idField, gc);

        gc.gridx = 0; gc.gridy = 3; gc.gridwidth = 2; card.add(checkBtn,  gc);
        gc.gridy = 4; card.add(statusLbl, gc);

        checkBtn.addActionListener(e -> {
            String id = idField.getText().trim();
            vendorService.findVendor(id).ifPresentOrElse(
                v -> {
                    String stallInfo = v.isAllocated() ? v.getStallId() : "Not yet allocated";
                    String msg = String.format(
                            "<html><b>Name:</b> %s<br><b>Market:</b> %s  <b>Shift:</b> %s<br>"
                          + "<b>Approved:</b> %s<br><b>Stall:</b> %s</html>",
                            v.getName(), v.getMarket(), v.getShift(),
                            v.isApproved() ? "✓ Yes" : "✗ Pending",
                            stallInfo);
                    statusLbl.setText(msg);
                },
                () -> {
                    statusLbl.setForeground(Theme.DANGER);
                    statusLbl.setText("Vendor not found: " + id);
                }
            );
        });

        outer.add(card);
        return outer;
    }

    
    // UTILITIES
    

    /** Functional interface for action-tab callbacks. */
    @FunctionalInterface
    private interface ActionCallback {
        void execute(String vendorId) throws Exception;
    }

    private void swap11(JPanel panel) {
        setContentPane(panel);
        revalidate();
        repaint();
    }

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to default L&F gracefully
        }
    }

    // MAIN METHOD

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VendorManagementSystem().setVisible(true));
    }
}