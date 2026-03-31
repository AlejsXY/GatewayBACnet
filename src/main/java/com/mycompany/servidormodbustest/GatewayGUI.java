package com.mycompany.servidormodbusTest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GatewayGUI extends JFrame {

    private static final long serialVersionUID = 1L;
    
    private static final String ARCHIVO_CONFIG = "config_ou_iu.txt";
    private static final String ARCHIVO_VARIABLES = "config_variables.txt";
    private static final String ARCHIVO_RED = "config_network.properties";
    private static final int REFRESCO_AUTOMATICO_MS = 5000;
    
    private JTabbedPane tabbedPane;
    private JTextField txtPuertoModbus, txtPuertoBacnet, txtIdLocal, txtTiempoDescubrimiento, txtIntervaloLectura;
    private JTextField txtIdOU, txtIUInicio, txtIUFin;
    private JTextArea txtLog;
    private JScrollPane scrollRegistros;
    private JTextArea textAreaDatos;
    private JLabel lblHoraActualizacionGlobal;
    private JComboBox<String> cbInterfazRed;
    
    private AtomicBoolean servidorActivo = new AtomicBoolean(false);
    private Thread threadServidor = null;
    private final javax.swing.Timer timerAutoRefresh;
    
    // Configuración de bloques OU-IU
    private List<ConfigBloqueOU> listaBloques = new ArrayList<>();
    private DefaultListModel<String> listModelOU = new DefaultListModel<>();
    private JList<String> listaOUUI;
    
    // Definiciones de variables globales
    private VariableDefinitions varDefs = new VariableDefinitions();
    
    // Tabla de variables editables
    private JTable tblVariables;
    private VariablesTableModel tblModelVariables;
    
    public GatewayGUI() {
        setTitle("Gateway BACnet → Modbus TCP");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1000, 600));
        
        initComponents();
        initConfiguracion();
        
        // Guardar configuración al cerrar la ventana
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                guardarConfiguracionCompleta();
            }
        });
        
        timerAutoRefresh = new javax.swing.Timer(REFRESCO_AUTOMATICO_MS, e -> {
            if (servidorActivo.get()) {
                actualizarPanelDatos(lblHoraActualizacionGlobal);
            }
        });
        
        setVisible(true);
    }
    
    @Override
    public void dispose() {
        if (timerAutoRefresh != null) {
            timerAutoRefresh.stop();
        }
        super.dispose();
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GatewayGUI());
    }
    
    // ========== Clases de datos ==========
    static class VariableConfig {
        String tipo;
        int instance;
        VariableConfig(String tipo, int instance) {
            this.tipo = tipo;
            this.instance = instance;
        }
        @Override
        public String toString() {
            return tipo + ":" + instance;
        }
    }
    
    static class VariableDefinitions {
        List<VariableConfig> ouVars = new ArrayList<>();
        List<VariableConfig> iuReadVars = new ArrayList<>();
        List<VariableConfig> iuWriteVars = new ArrayList<>();
        
        VariableDefinitions() {
            ouVars.add(new VariableConfig("multiStateInput", 1));
            ouVars.add(new VariableConfig("binaryInput", 1));
            ouVars.add(new VariableConfig("analogInput", 1));
            ouVars.add(new VariableConfig("analogInput", 2));
            ouVars.add(new VariableConfig("analogInput", 3));
            ouVars.add(new VariableConfig("analogInput", 8));
            
            iuReadVars.add(new VariableConfig("analogInput", 1));
            iuReadVars.add(new VariableConfig("analogInput", 2));
            
            iuWriteVars.add(new VariableConfig("binaryValue", 1));
            iuWriteVars.add(new VariableConfig("analogValue", 1));
        }
    }
    
    static class ConfigBloqueOU {
        int idOU;
        int idIUInicio;
        int idIUFin;
        ConfigBloqueOU(int idOU, int idIUInicio, int idIUFin) {
            this.idOU = idOU;
            this.idIUInicio = idIUInicio;
            this.idIUFin = idIUFin;
        }
        int getCantidadIU() {
            return idIUFin - idIUInicio + 1;
        }
    }
    
    // ========== Modelo de tabla ==========
    class VariablesTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Ubicación", "Variable", "R/W"};
        private List<VariableEntry> entries = new ArrayList<>();
        
        VariablesTableModel() {
            rebuildEntries();
        }
        
        void rebuildEntries() {
            entries.clear();
            for (int i = 0; i < 6; i++) {
                VariableConfig var = varDefs.ouVars.get(i);
                boolean soloLectura = var.tipo.endsWith("Input");
                String ubic = "OU Propia " + i;
                entries.add(new VariableEntry(ubic, var, soloLectura));
            }
            for (int i = 0; i < 2; i++) {
                VariableConfig var = varDefs.iuReadVars.get(i);
                boolean soloLectura = var.tipo.endsWith("Input");
                String ubic = "IU Lectura " + i;
                entries.add(new VariableEntry(ubic, var, soloLectura));
            }
            for (int i = 0; i < 2; i++) {
                VariableConfig var = varDefs.iuWriteVars.get(i);
                String ubic = "IU Escritura " + i;
                entries.add(new VariableEntry(ubic, var, false));
            }
            fireTableDataChanged();
        }
        
        VariableEntry getEntryAt(int row) {
            if (row >= 0 && row < entries.size()) return entries.get(row);
            return null;
        }
        
        @Override
        public int getRowCount() { return entries.size(); }
        @Override
        public int getColumnCount() { return 3; }
        @Override
        public String getColumnName(int col) { return columnNames[col]; }
        
        @Override
        public Object getValueAt(int row, int col) {
            VariableEntry e = entries.get(row);
            if (col == 0) return e.ubicacion;
            if (col == 1) return e.var.toString();
            if (col == 2) return e.soloLectura ? "R" : "W";
            return null;
        }
        
        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    }
    
    class VariableEntry {
        String ubicacion;
        VariableConfig var;
        boolean soloLectura;
        
        VariableEntry(String ubicacion, VariableConfig var, boolean soloLectura) {
            this.ubicacion = ubicacion;
            this.var = var;
            this.soloLectura = soloLectura;
        }
        
        VariableConfig getVariable() { return var; }
        void setVariable(VariableConfig newVar) { this.var = newVar; }
    }
    
    // ========== Inicialización y persistencia ==========
    private void initConfiguracion() {
        // Cargar bloques OU
        if (!cargarConfiguracionBloques()) {
            listaBloques.clear();
            for (ConfigBloqueOU bloqueDefault : getBloquesDefault()) {
                listaBloques.add(bloqueDefault);
            }
        }
        actualizarListaOU();
        
        // Cargar definiciones de variables
        if (!cargarVariablesDefiniciones()) {
            // usar valores por defecto ya en varDefs
        }
        actualizarTablaVariables();
        
        // Cargar configuración de red
        cargarConfiguracionRed();
    }
    
    private void guardarConfiguracionCompleta() {
        guardarConfiguracionBloques();
        guardarVariablesDefiniciones();
        guardarConfiguracionRed();
    }
    
    // ---- Persistencia de parámetros de red ----
    private void guardarConfiguracionRed() {
        Properties props = new Properties();
        props.setProperty("modbus.port", txtPuertoModbus.getText().trim());
        props.setProperty("bacnet.port", txtPuertoBacnet.getText().trim());
        props.setProperty("local.id", txtIdLocal.getText().trim());
        props.setProperty("discovery.timeout", txtTiempoDescubrimiento.getText().trim());
        props.setProperty("read.interval", txtIntervaloLectura.getText().trim());
        
        String selected = (String) cbInterfazRed.getSelectedItem();
        if (selected != null && !selected.isEmpty() && selected.contains("(")) {
            String ip = selected.substring(selected.lastIndexOf('(') + 1, selected.lastIndexOf(')'));
            props.setProperty("network.ip", ip);
        }
        
        try (FileOutputStream out = new FileOutputStream(ARCHIVO_RED)) {
            props.store(out, "Configuración de red del gateway");
            agregarLog("Configuración de red guardada.");
        } catch (IOException e) {
            agregarLog("Error al guardar configuración de red: " + e.getMessage());
        }
    }
    
    private void cargarConfiguracionRed() {
        File archivo = new File(ARCHIVO_RED);
        if (!archivo.exists()) {
            agregarLog("No se encontró configuración de red previa. Usando valores por defecto.");
            return;
        }
        
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(archivo)) {
            props.load(in);
            
            txtPuertoModbus.setText(props.getProperty("modbus.port", "503"));
            txtPuertoBacnet.setText(props.getProperty("bacnet.port", "47808"));
            txtIdLocal.setText(props.getProperty("local.id", "12345"));
            txtTiempoDescubrimiento.setText(props.getProperty("discovery.timeout", "2"));
            txtIntervaloLectura.setText(props.getProperty("read.interval", "2000"));
            
            String ipGuardada = props.getProperty("network.ip");
            if (ipGuardada != null && !ipGuardada.isEmpty()) {
                for (int i = 0; i < cbInterfazRed.getItemCount(); i++) {
                    String item = cbInterfazRed.getItemAt(i);
                    if (item.contains(ipGuardada)) {
                        cbInterfazRed.setSelectedIndex(i);
                        break;
                    }
                }
            }
            agregarLog("Configuración de red cargada.");
        } catch (IOException e) {
            agregarLog("Error al cargar configuración de red: " + e.getMessage());
        }
    }
    
    // ---- Persistencia de bloques OU ----
    private void guardarConfiguracionBloques() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_CONFIG))) {
            writer.println("# Configuración de Bloques OU-IU");
            writer.println("# Formato: idOU,idIUInicio,idIUFin");
            for (ConfigBloqueOU bloque : listaBloques) {
                writer.println(bloque.idOU + "," + bloque.idIUInicio + "," + bloque.idIUFin);
            }
        } catch (IOException e) {
            agregarLog("Error al guardar configuración de bloques: " + e.getMessage());
        }
    }
    
    private boolean cargarConfiguracionBloques() {
        File archivo = new File(ARCHIVO_CONFIG);
        if (!archivo.exists()) return false;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            listaBloques.clear();
            String linea;
            while ((linea = reader.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) continue;
                String[] partes = linea.split(",");
                if (partes.length >= 3) {
                    try {
                        int idOU = Integer.parseInt(partes[0].trim());
                        int idIUInicio = Integer.parseInt(partes[1].trim());
                        int idIUFin = Integer.parseInt(partes[2].trim());
                        listaBloques.add(new ConfigBloqueOU(idOU, idIUInicio, idIUFin));
                    } catch (NumberFormatException e) { /* ignorar */ }
                }
            }
            agregarLog("Bloques OU cargados: " + listaBloques.size());
            return true;
        } catch (IOException e) {
            agregarLog("Error al cargar configuración de bloques: " + e.getMessage());
            return false;
        }
    }
    
    // ---- Persistencia de variables ----
    private void guardarVariablesDefiniciones() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_VARIABLES))) {
            writer.println("# Configuración de variables globales");
            writer.println("# Formato: tipo,instancia");
            writer.println("# OU variables (6)");
            for (VariableConfig v : varDefs.ouVars) {
                writer.println(v.tipo + "," + v.instance);
            }
            writer.println("# IU Read variables (2)");
            for (VariableConfig v : varDefs.iuReadVars) {
                writer.println(v.tipo + "," + v.instance);
            }
            writer.println("# IU Write variables (2)");
            for (VariableConfig v : varDefs.iuWriteVars) {
                writer.println(v.tipo + "," + v.instance);
            }
            agregarLog("Variables globales guardadas.");
        } catch (IOException e) {
            agregarLog("Error al guardar variables: " + e.getMessage());
        }
    }
    
    private boolean cargarVariablesDefiniciones() {
        File archivo = new File(ARCHIVO_VARIABLES);
        if (!archivo.exists()) {
            agregarLog("Archivo de variables no encontrado. Usando valores por defecto.");
            return false;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            varDefs.ouVars.clear();
            varDefs.iuReadVars.clear();
            varDefs.iuWriteVars.clear();
            
            String linea;
            int estado = 0; // 0: OU, 1: IU Read, 2: IU Write
            int ouCount = 0, readCount = 0, writeCount = 0;
            
            while ((linea = reader.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty()) continue;
                
                if (linea.startsWith("# OU")) {
                    estado = 0;
                    continue;
                } else if (linea.startsWith("# IU Read")) {
                    estado = 1;
                    continue;
                } else if (linea.startsWith("# IU Write")) {
                    estado = 2;
                    continue;
                }
                if (linea.startsWith("#")) continue;
                
                String[] partes = linea.split(",");
                if (partes.length != 2) continue;
                
                String tipo = partes[0].trim();
                int inst;
                try {
                    inst = Integer.parseInt(partes[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                
                VariableConfig vc = new VariableConfig(tipo, inst);
                if (estado == 0 && ouCount < 6) {
                    varDefs.ouVars.add(vc);
                    ouCount++;
                } else if (estado == 1 && readCount < 2) {
                    varDefs.iuReadVars.add(vc);
                    readCount++;
                } else if (estado == 2 && writeCount < 2) {
                    varDefs.iuWriteVars.add(vc);
                    writeCount++;
                }
            }
            
            // Completar con defaults si faltan
            if (ouCount < 6) {
                List<VariableConfig> defaultOU = Arrays.asList(
                    new VariableConfig("multiStateInput", 1),
                    new VariableConfig("binaryInput", 1),
                    new VariableConfig("analogInput", 1),
                    new VariableConfig("analogInput", 2),
                    new VariableConfig("analogInput", 3),
                    new VariableConfig("analogInput", 8)
                );
                for (int i = varDefs.ouVars.size(); i < 6; i++) {
                    varDefs.ouVars.add(defaultOU.get(i));
                }
            }
            if (readCount < 2) {
                List<VariableConfig> defaultRead = Arrays.asList(
                    new VariableConfig("analogInput", 1),
                    new VariableConfig("analogInput", 2)
                );
                for (int i = varDefs.iuReadVars.size(); i < 2; i++) {
                    varDefs.iuReadVars.add(defaultRead.get(i));
                }
            }
            if (writeCount < 2) {
                List<VariableConfig> defaultWrite = Arrays.asList(
                    new VariableConfig("binaryValue", 1),
                    new VariableConfig("analogValue", 1)
                );
                for (int i = varDefs.iuWriteVars.size(); i < 2; i++) {
                    varDefs.iuWriteVars.add(defaultWrite.get(i));
                }
            }
            
            agregarLog("Variables globales cargadas: " + ouCount + " OU, " + readCount + " lectura, " + writeCount + " escritura.");
            return true;
        } catch (IOException e) {
            agregarLog("Error al cargar variables: " + e.getMessage());
            return false;
        }
    }
    
    private List<ConfigBloqueOU> getBloquesDefault() {
        List<ConfigBloqueOU> defaults = new ArrayList<>();
        defaults.add(new ConfigBloqueOU(50100, 50000, 50007));
        defaults.add(new ConfigBloqueOU(51112, 51001, 51009));
        defaults.add(new ConfigBloqueOU(52120, 52001, 52015));
        defaults.add(new ConfigBloqueOU(53124, 53001, 53009));
        return defaults;
    }
    
    // ========== Métodos auxiliares (implementados) ==========
    private void actualizarTablaVariables() {
        if (tblModelVariables != null) {
            tblModelVariables.rebuildEntries();
        }
    }
    
    private VariableConfig mostrarDialogoVariable(boolean soloLectura, VariableConfig actual) {
        String[] tipos;
        if (soloLectura) {
            tipos = new String[]{"binaryInput", "analogInput", "multiStateInput"};
        } else {
            tipos = new String[]{"binaryValue", "analogValue", "multiStateValue"};
        }
        
        String tipoSeleccionado = (String) JOptionPane.showInputDialog(
                this, "Seleccione el tipo de objeto BACnet:", "Editar Variable",
                JOptionPane.QUESTION_MESSAGE, null, tipos, actual != null ? actual.tipo : tipos[0]);
        if (tipoSeleccionado == null) return null;
        
        String instStr = JOptionPane.showInputDialog(this, "Ingrese la instancia BACnet:",
                "Instancia", JOptionPane.QUESTION_MESSAGE);
        if (instStr == null) return null;
        int instance;
        try {
            instance = Integer.parseInt(instStr.trim());
            if (instance < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Instancia inválida. Debe ser un número entero no negativo.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        
        if (soloLectura) {
            if (!tipoSeleccionado.endsWith("Input") && !tipoSeleccionado.equals("multiStateInput")) {
                JOptionPane.showMessageDialog(this,
                        "Error: Esta posición solo admite variables de lectura (binaryInput o analogInput).",
                        "Tipo no permitido", JOptionPane.ERROR_MESSAGE);
                return null;
            }
        } else {
            if (!tipoSeleccionado.endsWith("Value") && !tipoSeleccionado.equals("multiStateValue")) {
                JOptionPane.showMessageDialog(this,
                        "Error: Esta posición solo admite variables de lectura/escritura (binaryValue, analogValue, multiStateValue).",
                        "Tipo no permitido", JOptionPane.ERROR_MESSAGE);
                return null;
            }
        }
        
        return new VariableConfig(tipoSeleccionado, instance);
    }
    
    private void agregarBloqueOU() {
        String idOUStr = txtIdOU.getText().trim();
        String idIUInicioStr = txtIUInicio.getText().trim();
        String idIUFinStr = txtIUFin.getText().trim();
        
        if (idOUStr.isEmpty() || idIUInicioStr.isEmpty() || idIUFinStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Todos los campos deben estar llenos.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            int idOU = Integer.parseInt(idOUStr);
            int idIUInicio = Integer.parseInt(idIUInicioStr);
            int idIUFin = Integer.parseInt(idIUFinStr);
            
            if (idOU < 0 || idIUInicio < 0 || idIUFin < 0) throw new NumberFormatException();
            if (idIUInicio > idIUFin) {
                JOptionPane.showMessageDialog(this, "El ID IU Inicial debe ser menor o igual que el ID IU Final.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            boolean existe = listaBloques.stream().anyMatch(b -> b.idOU == idOU);
            if (existe) {
                int opcion = JOptionPane.showConfirmDialog(this,
                        "Ya existe un bloque con OU " + idOU + ". ¿Desea reemplazarlo?",
                        "OU duplicada", JOptionPane.YES_NO_OPTION);
                if (opcion != JOptionPane.YES_OPTION) return;
                listaBloques.removeIf(b -> b.idOU == idOU);
            }
            
            listaBloques.add(new ConfigBloqueOU(idOU, idIUInicio, idIUFin));
            Collections.sort(listaBloques, (a, b) -> Integer.compare(a.idOU, b.idOU));
            
            actualizarListaOU();
            guardarConfiguracionBloques();
            agregarLog("Bloque agregado: OU=" + idOU + ", IUs " + idIUInicio + " - " + idIUFin);
            txtIdOU.setText("");
            txtIUInicio.setText("");
            txtIUFin.setText("");
            
            mostrarAvisoReinicio();
            
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Los IDs deben ser números enteros válidos.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void eliminarBloqueOU() {
        int idx = listaOUUI.getSelectedIndex();
        if (idx == -1) {
            JOptionPane.showMessageDialog(this, "Seleccione un bloque OU-IU para eliminar.", "Sin selección", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ConfigBloqueOU bloque = listaBloques.get(idx);
        int opcion = JOptionPane.showConfirmDialog(this, "¿Eliminar OU-" + bloque.idOU + "?\nEsto eliminará el bloque y sus IUs.", "Confirmar", JOptionPane.YES_NO_OPTION);
        if (opcion == JOptionPane.YES_OPTION) {
            listaBloques.remove(idx);
            actualizarListaOU();
            guardarConfiguracionBloques();
            agregarLog("OU-" + bloque.idOU + " eliminada.");
            mostrarAvisoReinicio();
        }
    }
    
    private void restaurarDefaults() {
        int opcion = JOptionPane.showConfirmDialog(this, "Restaurar los 4 bloques OU-IU por defecto y las variables por defecto?\nEsto eliminará cualquier configuración personalizada.", "Confirmar", JOptionPane.YES_NO_OPTION);
        if (opcion == JOptionPane.YES_OPTION) {
            listaBloques.clear();
            for (ConfigBloqueOU bloqueDefault : getBloquesDefault()) {
                listaBloques.add(bloqueDefault);
            }
            varDefs = new VariableDefinitions(); // restaura valores por defecto
            actualizarListaOU();
            actualizarTablaVariables();
            guardarConfiguracionCompleta();
            agregarLog("Configuración restaurada a defaults (4 bloques y variables por defecto).");
            mostrarAvisoReinicio();
        }
    }
    
    private void actualizarListaOU() {
        listModelOU.clear();
        for (ConfigBloqueOU bloque : listaBloques) {
            listModelOU.addElement(String.format("OU: %d  |  IUs: %d - %d  |  (%d unidades)",
                    bloque.idOU, bloque.idIUInicio, bloque.idIUFin, bloque.getCantidadIU()));
        }
    }
    
    private void cargarInterfacesRed() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (net.isUp() && !net.isLoopback() && !net.isVirtual()) {
                    for (InterfaceAddress addr : net.getInterfaceAddresses()) {
                        InetAddress inet = addr.getAddress();
                        if (inet instanceof Inet4Address && !inet.isLinkLocalAddress()) {
                            String display = net.getDisplayName() + " (" + inet.getHostAddress() + ")";
                            cbInterfazRed.addItem(display);
                            break;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        if (cbInterfazRed.getItemCount() == 0) {
            cbInterfazRed.addItem("No se encontraron interfaces IPv4");
            cbInterfazRed.setEnabled(false);
        }
    }
    
    private void mostrarAvisoReinicio() {
        if (servidorActivo.get()) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "Configuración modificada. Reinicie el servidor para que los cambios tengan efecto.",
                        "Configuración cambiada", JOptionPane.WARNING_MESSAGE);
            });
            agregarLog("ADVERTENCIA: Configuración modificada. Reinicie el servidor.");
        }
    }
    
    private void agregarLog(String mensaje) {
        String hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        txtLog.append("[" + hora + "] " + mensaje + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
    
    // ========== Panel de Configuración ==========
    private JPanel crearPanelConfiguracion() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        
        JPanel panelSuperior = new JPanel(new GridLayout(1, 2, 15, 0));
        
        // Panel izquierdo: Parámetros de red y bloques OU-IU
        JPanel panelIzquierdo = new JPanel(new BorderLayout(10, 10));
        panelIzquierdo.setBorder(new TitledBorder("Parámetros de Red y Bloques OU-IU"));
        
        JPanel gridPuertos = new JPanel(new GridLayout(5, 2, 10, 10));
        gridPuertos.add(new JLabel("Puerto Modbus:"));
        txtPuertoModbus = new JTextField("503", 10);
        gridPuertos.add(txtPuertoModbus);
        gridPuertos.add(new JLabel("Puerto BACnet:"));
        txtPuertoBacnet = new JTextField("47808", 10);
        gridPuertos.add(txtPuertoBacnet);
        gridPuertos.add(new JLabel("ID Dispositivo Local:"));
        txtIdLocal = new JTextField("12345", 10);
        gridPuertos.add(txtIdLocal);
        gridPuertos.add(new JLabel("Tiempo Descubrimiento (s):"));
        txtTiempoDescubrimiento = new JTextField("2", 10);
        gridPuertos.add(txtTiempoDescubrimiento);
        gridPuertos.add(new JLabel("Intervalo Lectura (ms):"));
        txtIntervaloLectura = new JTextField("2000", 10);
        gridPuertos.add(txtIntervaloLectura);
        panelIzquierdo.add(gridPuertos, BorderLayout.NORTH);
        
        // Panel de bloques OU-IU
        JPanel panelOU = new JPanel(new BorderLayout(5, 5));
        panelOU.setBorder(new TitledBorder("Bloques OU-IU Configurados"));
        
        JPanel panelControlesOU = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        panelControlesOU.add(new JLabel("OU:"));
        txtIdOU = new JTextField(5);
        panelControlesOU.add(txtIdOU);
        panelControlesOU.add(new JLabel("IU Inicio:"));
        txtIUInicio = new JTextField(5);
        panelControlesOU.add(txtIUInicio);
        panelControlesOU.add(new JLabel("IU Fin:"));
        txtIUFin = new JTextField(5);
        panelControlesOU.add(txtIUFin);
        
        JButton btnAgregarOU = new JButton("Agregar");
        btnAgregarOU.addActionListener(e -> agregarBloqueOU());
        panelControlesOU.add(btnAgregarOU);
        
        JButton btnEliminarOU = new JButton("Eliminar");
        btnEliminarOU.addActionListener(e -> eliminarBloqueOU());
        panelControlesOU.add(btnEliminarOU);
        
        JButton btnGuardarOU = new JButton("Guardar");
        btnGuardarOU.addActionListener(e -> guardarConfiguracionCompleta());
        panelControlesOU.add(btnGuardarOU);
        
        JButton btnRestaurarDefaults = new JButton("Defaults");
        btnRestaurarDefaults.addActionListener(e -> restaurarDefaults());
        panelControlesOU.add(btnRestaurarDefaults);
        
        panelOU.add(panelControlesOU, BorderLayout.NORTH);
        
        listaOUUI = new JList<>(listModelOU);
        listaOUUI.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollOU = new JScrollPane(listaOUUI);
        scrollOU.setPreferredSize(new Dimension(250, 120));
        panelOU.add(scrollOU, BorderLayout.CENTER);
        
        panelIzquierdo.add(panelOU, BorderLayout.CENTER);
        
        // Selector de interfaz de red
        JPanel panelInterfaz = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panelInterfaz.add(new JLabel("Interfaz de red:"));
        cbInterfazRed = new JComboBox<>();
        cbInterfazRed.setPreferredSize(new Dimension(300, 25));
        cargarInterfacesRed();
        panelInterfaz.add(cbInterfazRed);
        panelIzquierdo.add(panelInterfaz, BorderLayout.SOUTH);
        
        // Panel derecho: Tabla editable de variables globales
        JPanel panelDerecho = new JPanel(new BorderLayout(10, 10));
        panelDerecho.setBorder(new TitledBorder("Variables a Leer/Escribir (globales)"));
        
        tblModelVariables = new VariablesTableModel();
        tblVariables = new JTable(tblModelVariables);
        tblVariables.setFont(new Font("Consolas", Font.PLAIN, 12));
        tblVariables.getColumnModel().getColumn(0).setPreferredWidth(150);
        tblVariables.getColumnModel().getColumn(1).setPreferredWidth(200);
        tblVariables.getColumnModel().getColumn(2).setPreferredWidth(50);
        tblVariables.setRowHeight(22);
        tblVariables.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (column == 2) {
                    if ("R".equals(value)) c.setForeground(Color.BLUE);
                    else c.setForeground(new Color(0, 100, 0));
                } else {
                    c.setForeground(Color.BLACK);
                }
                return c;
            }
        });
        tblVariables.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tblVariables.getSelectedRow();
                    if (row >= 0) {
                        VariableEntry entry = tblModelVariables.getEntryAt(row);
                        if (entry == null) return;
                        VariableConfig nueva = mostrarDialogoVariable(entry.soloLectura, entry.getVariable());
                        if (nueva != null) {
                            if (row < 6) {
                                varDefs.ouVars.set(row, nueva);
                            } else if (row < 8) {
                                varDefs.iuReadVars.set(row - 6, nueva);
                            } else {
                                varDefs.iuWriteVars.set(row - 8, nueva);
                            }
                            tblModelVariables.rebuildEntries();
                            guardarVariablesDefiniciones();
                            mostrarAvisoReinicio();
                        }
                    }
                }
            }
        });
        JScrollPane scrollVariables = new JScrollPane(tblVariables);
        scrollVariables.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        panelDerecho.add(scrollVariables, BorderLayout.CENTER);
        
        JPanel panelBotonesInicio = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panelBotonesInicio.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        
        JButton btnIniciar = new JButton("  INICIAR SERVIDOR  ");
        btnIniciar.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnIniciar.setPreferredSize(new Dimension(180, 40));
        btnIniciar.setBackground(new Color(76, 175, 80));
        btnIniciar.setForeground(Color.WHITE);
        btnIniciar.setFocusPainted(false);
        btnIniciar.setOpaque(true);
        btnIniciar.setBorderPainted(false);
        btnIniciar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnIniciar.addActionListener(e -> iniciarServidor());
        
        JButton btnDetener = new JButton("  DETENER  ");
        btnDetener.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnDetener.setPreferredSize(new Dimension(180, 40));
        btnDetener.setBackground(new Color(244, 67, 54));
        btnDetener.setForeground(Color.WHITE);
        btnDetener.setFocusPainted(false);
        btnDetener.setOpaque(true);
        btnDetener.setBorderPainted(false);
        btnDetener.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnDetener.addActionListener(e -> detenerServidor());
        
        panelBotonesInicio.add(btnIniciar);
        panelBotonesInicio.add(btnDetener);
        panelDerecho.add(panelBotonesInicio, BorderLayout.SOUTH);
        
        panelSuperior.add(panelIzquierdo);
        panelSuperior.add(panelDerecho);
        panel.add(panelSuperior, BorderLayout.NORTH);
        
        JPanel panelCentro = new JPanel(new BorderLayout(10, 10));
        panelCentro.setBorder(new TitledBorder("Log de Acciones"));
        txtLog = new JTextArea(8, 0);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtLog.setEditable(false);
        txtLog.setBackground(new Color(245, 245, 245));
        panelCentro.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        panel.add(panelCentro, BorderLayout.CENTER);
        
        panel.validate();
        panel.repaint();
        
        return panel;
    }
    
    // ========== Panel Ver Datos ==========
    private JPanel crearPanelVerDatos() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JPanel panelSuperior = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panelSuperior.add(new JLabel("Última actualización: "));
        JLabel lblHoraActualizacion = new JLabel("--:--:--");
        lblHoraActualizacion.setFont(new Font("Segoe UI", Font.BOLD, 13));
        panelSuperior.add(lblHoraActualizacion);
        lblHoraActualizacionGlobal = lblHoraActualizacion;
        
        JButton btnRefrescar = new JButton("Refrescar");
        btnRefrescar.addActionListener(e -> actualizarPanelDatos(lblHoraActualizacion));
        panelSuperior.add(btnRefrescar);
        panel.add(panelSuperior, BorderLayout.NORTH);
        
        textAreaDatos = new JTextArea();
        textAreaDatos.setEditable(false);
        textAreaDatos.setFont(new Font("Consolas", Font.PLAIN, 12));
        textAreaDatos.setBackground(new Color(250, 250, 250));
        
        scrollRegistros = new JScrollPane(textAreaDatos);
        scrollRegistros.getVerticalScrollBar().setUnitIncrement(20);
        scrollRegistros.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scrollRegistros, BorderLayout.CENTER);
        
        actualizarPanelDatos(lblHoraActualizacion);
        return panel;
    }
    
    private void actualizarPanelDatos(JLabel lblHora) {
        int posScroll = 0;
        if (scrollRegistros != null && scrollRegistros.getVerticalScrollBar() != null) {
            posScroll = scrollRegistros.getVerticalScrollBar().getValue();
        }
        
        if (!servidorActivo.get()) {
            lblHora.setText("SERVIDOR DETENIDO");
            lblHora.setForeground(Color.GRAY);
            textAreaDatos.setText("Inicie el servidor para ver los datos.\nConfigure los bloques OU-IU en la pestaña Configuración.");
            textAreaDatos.setForeground(Color.GRAY);
            if (scrollRegistros != null && scrollRegistros.getVerticalScrollBar() != null) {
                scrollRegistros.getVerticalScrollBar().setValue(posScroll);
            }
            return;
        }
        
        lblHora.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        lblHora.setForeground(new Color(0, 100, 0));
        textAreaDatos.setForeground(Color.BLACK);
        
        Map<Integer, Integer> valores = ServidorModbusTest.obtenerValoresRegistros();
        Map<Integer, String> nombres = ServidorModbusTest.obtenerNombresRegistros();
        StringBuilder sb = new StringBuilder();
        
        if (listaBloques.isEmpty()) {
            sb.append("No hay bloques OU-IU configurados.\n");
            sb.append("Vaya a Configuración para agregar bloques.\n");
        } else {
            // Reconstruir la estructura jerárquica igual que antes
            int offset = 0;
            for (int ouIdx = 0; ouIdx < listaBloques.size(); ouIdx++) {
                ConfigBloqueOU bloque = listaBloques.get(ouIdx);
                int cantIU = bloque.getCantidadIU();
                int tamLectura = 6 + cantIU * 2;
                int base = offset;
                offset += tamLectura;
                
                sb.append("\nOU-").append(ouIdx).append(" ID: ").append(bloque.idOU).append("\n");
                sb.append("------------------------------------------------\n");
                sb.append("  Vars Propias OU\n");
                sb.append(String.format("  %-5s %-4s %7s  %s\n", "Reg", "Idx", "Valor", "Variable"));
                sb.append("  ------------------------------------------------\n");
                for (int i = 0; i < 6; i++) {
                    int dir = base + i;
                    int valor = valores.getOrDefault(dir, 0);
                    String nombre = nombres.getOrDefault(dir, "Var" + i);
                    sb.append(String.format("  %-5d %-4d [%5d]  %s\n", dir, i, valor, nombre));
                }
                
                // Lectura por IU
                int baseIU = base + 6;
                for (int iu = 0; iu < cantIU; iu++) {
                    int idIU = bloque.idIUInicio + iu;
                    sb.append("\n  IU-").append(iu).append(" ID: ").append(idIU).append("\n");
                    sb.append(String.format("  %-5s %-4s %7s  %s\n", "Reg", "Idx", "Valor", "Variable"));
                    sb.append("  ------------------------------------------------\n");
                    for (int j = 0; j < 2; j++) {
                        int dir = baseIU + iu*2 + j;
                        int valor = valores.getOrDefault(dir, 0);
                        String nombre = nombres.getOrDefault(dir, "Var" + j);
                        sb.append(String.format("  %-5d %-4d [%5d]  %s\n", dir, j, valor, nombre));
                    }
                }
            }
            
            // Bloque de escritura al final
            int baseEscritura = offset;
            int iuGlobal = 0;
            sb.append("\n================================================\n");
            sb.append("  BLOQUE DE ESCRITURA (lectura/escritura)\n");
            sb.append("================================================\n");
            for (ConfigBloqueOU bloque : listaBloques) {
                int cantIU = bloque.getCantidadIU();
                for (int iu = 0; iu < cantIU; iu++) {
                    int idIU = bloque.idIUInicio + iu;
                    int dirBase = baseEscritura + iuGlobal * 2;
                    sb.append("\n  IU-").append(iu).append(" (global ").append(iuGlobal).append(") ID: ").append(idIU).append("\n");
                    sb.append(String.format("  %-5s %-4s %7s  %s\n", "Reg", "Idx", "Valor", "Variable"));
                    sb.append("  ------------------------------------------------\n");
                    for (int j = 0; j < 2; j++) {
                        int dir = dirBase + j;
                        int valor = valores.getOrDefault(dir, 0);
                        String nombre = nombres.getOrDefault(dir, "Var" + j);
                        sb.append(String.format("  %-5d %-4d [%5d]  %s\n", dir, j, valor, nombre));
                    }
                    iuGlobal++;
                }
            }
        }
        
        textAreaDatos.setText(sb.toString());
        
        if (scrollRegistros != null && scrollRegistros.getVerticalScrollBar() != null) {
            final int scrollPos = posScroll;
            SwingUtilities.invokeLater(() -> scrollRegistros.getVerticalScrollBar().setValue(scrollPos));
        }
    }
    
    // ========== Control del servidor ==========
    private void iniciarServidor() {
        if (servidorActivo.get()) {
            JOptionPane.showMessageDialog(this, "El servidor ya está activo.", "Información", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        try {
            int puerto = Integer.parseInt(txtPuertoModbus.getText().trim());
            int puertoBacnet = Integer.parseInt(txtPuertoBacnet.getText().trim());
            int idLocal = Integer.parseInt(txtIdLocal.getText().trim());
            int tiempoDescubrimiento = Integer.parseInt(txtTiempoDescubrimiento.getText().trim()) * 1000; // convertir a ms
            int intervaloLectura = Integer.parseInt(txtIntervaloLectura.getText().trim());
            
            if (puerto < 1 || puerto > 65535 || puertoBacnet < 1 || puertoBacnet > 65535 ||
                tiempoDescubrimiento < 0 || intervaloLectura < 0) {
                JOptionPane.showMessageDialog(this, "Parámetros inválidos.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String selected = (String) cbInterfazRed.getSelectedItem();
            if (selected == null || selected.isEmpty() || selected.contains("No se encontraron")) {
                JOptionPane.showMessageDialog(this, "Debe seleccionar una interfaz de red válida.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String ipSeleccionada = selected.substring(selected.lastIndexOf('(') + 1, selected.lastIndexOf(')'));
            
            agregarLog("Iniciando servidor...");
            agregarLog("Puerto Modbus: " + puerto);
            agregarLog("Puerto BACnet: " + puertoBacnet);
            agregarLog("ID Local: " + idLocal);
            agregarLog("Tiempo descubrimiento: " + (tiempoDescubrimiento / 1000) + " s");
            agregarLog("Intervalo lectura: " + intervaloLectura + " ms");
            agregarLog("Interfaz de red: " + selected);
            agregarLog("IP seleccionada: " + ipSeleccionada);
            agregarLog("OU configuradas: " + listaBloques.size());
            
            threadServidor = new Thread(() -> {
                try {
                    ServidorModbusTest.ejecutarServidor(puerto, puertoBacnet, idLocal, 
                            listaBloques, varDefs, ipSeleccionada,
                            tiempoDescubrimiento, intervaloLectura);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        agregarLog("ERROR: " + ex.getMessage());
                        servidorActivo.set(false);
                        timerAutoRefresh.stop();
                    });
                }
            });
            threadServidor.start();
            
            servidorActivo.set(true);
            timerAutoRefresh.start();
            
            agregarLog("Servidor iniciado correctamente.");
            JOptionPane.showMessageDialog(this, "Servidor iniciado correctamente.", "Éxito", JOptionPane.INFORMATION_MESSAGE);
            
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Parámetros inválidos.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void detenerServidor() {
        if (!servidorActivo.get()) {
            JOptionPane.showMessageDialog(this, "El servidor no está activo.", "Información", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        int opcion = JOptionPane.showConfirmDialog(this, "¿Detener el servidor?", "Confirmar", JOptionPane.YES_NO_OPTION);
        if (opcion != JOptionPane.YES_OPTION) return;
        
        try {
            ServidorModbusTest.detenerServidor();
            servidorActivo.set(false);
            timerAutoRefresh.stop();
            agregarLog("Servidor detenido.");
            actualizarPanelDatos(new JLabel());
            JOptionPane.showMessageDialog(this, "Servidor detenido.", "Información", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            agregarLog("Error al detener: " + ex.getMessage());
        }
    }
    
    private void initComponents() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        add(tabbedPane);
        
        tabbedPane.addTab("Configuración", crearPanelConfiguracion());
        tabbedPane.addTab("Ver Datos", crearPanelVerDatos());
    }
}