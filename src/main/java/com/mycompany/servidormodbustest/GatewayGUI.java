package com.mycompany.servidormodbusTest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GatewayGUI extends JFrame {

    private static final long serialVersionUID = 1L;
    
    private static final String ARCHIVO_CONFIG = "config_ou_iu.txt";
    
    private JTabbedPane tabbedPane;
    private JTextField txtPuertoModbus, txtPuertoBacnet, txtIdLocal, txtTiempoDescubrimiento, txtIntervaloLectura;
    private JTable tblVariables;
    private DefaultTableModel tblModelVariables;
    private JTextArea txtLog;
    private JPanel panelRegistros;
    private JScrollPane scrollRegistros;
    private JTextArea textAreaDatos;
    private JLabel lblHoraActualizacionGlobal;
    
    private AtomicBoolean servidorActivo = new AtomicBoolean(false);
    private Thread threadServidor = null;
    
    private List<ConfigOU> listaOU = new ArrayList<>();
    private DefaultListModel<String> listModelOU = new DefaultListModel<>();
    private JList<String> listaOUUI;
    private List<VariableConfig> listaVariables = new ArrayList<>();
    
    public GatewayGUI() {
        setTitle("Gateway BACnet → Modbus TCP");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1000, 600));
        
        initComponents();
        initConfiguracion();
        setVisible(true);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GatewayGUI());
    }
    
    private void initConfiguracion() {
        listaVariables.add(new VariableConfig("binaryInput", 1, 0));
        listaVariables.add(new VariableConfig("binaryInput", 2, 1));
        listaVariables.add(new VariableConfig("analogInput", 1, 2));
        listaVariables.add(new VariableConfig("analogInput", 2, 3));
        listaVariables.add(new VariableConfig("analogInput", 3, 4));
        listaVariables.add(new VariableConfig("analogInput", 8, 5));
        listaVariables.add(new VariableConfig("analogInput", 9, 6));
        listaVariables.add(new VariableConfig("analogInput", 10, 7));
        listaVariables.add(new VariableConfig("analogValue", 1, 8));
        listaVariables.add(new VariableConfig("analogValue", 6, 9));
        listaVariables.add(new VariableConfig("analogInput", 7, 10));
        listaVariables.add(new VariableConfig("binaryValue", 1, 11));
        listaVariables.add(new VariableConfig("binaryValue", 4, 12));
        listaVariables.add(new VariableConfig("multiStateValue", 1, 13));
        listaVariables.add(new VariableConfig("multiStateValue", 2, 14));
        
        if (!cargarConfiguracion()) {
            listaOU.addAll(ServidorModbusTest.getConfiguracionDefault());
        }
        actualizarListaOU();
    }
    
    private void initComponents() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        add(tabbedPane);
        
        tabbedPane.addTab("Configuracion", crearPanelConfiguracion());
        tabbedPane.addTab("Ver Datos", crearPanelVerDatos());
    }
    
    private JPanel crearPanelConfiguracion() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        
        JPanel panelSuperior = new JPanel(new BorderLayout(10, 0));
        
        JPanel panelIzquierdo = new JPanel(new BorderLayout(10, 10));
        panelIzquierdo.setBorder(new TitledBorder("Parametros de Red"));
        
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
        txtTiempoDescubrimiento = new JTextField("10", 10);
        gridPuertos.add(txtTiempoDescubrimiento);
        
        gridPuertos.add(new JLabel("Intervalo Lectura (ms):"));
        txtIntervaloLectura = new JTextField("10000", 10);
        gridPuertos.add(txtIntervaloLectura);
        
        panelIzquierdo.add(gridPuertos, BorderLayout.NORTH);
        
        JPanel panelOU = new JPanel(new BorderLayout(5, 5));
        panelOU.setBorder(new TitledBorder("Bloques OU-IU Configurados"));
        
        JPanel panelAgregarOU = new JPanel(new GridLayout(0, 1, 5, 5));
        
        JPanel fila1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fila1.add(new JLabel("ID OU:"));
        JTextField txtIdOU = new JTextField(10);
        fila1.add(txtIdOU);
        panelAgregarOU.add(fila1);
        
        JPanel fila2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fila2.add(new JLabel("ID IU Inicial:"));
        JTextField txtIUInicio = new JTextField(10);
        fila2.add(txtIUInicio);
        panelAgregarOU.add(fila2);
        
        JPanel fila3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fila3.add(new JLabel("ID IU Final:"));
        JTextField txtIUFin = new JTextField(10);
        fila3.add(txtIUFin);
        panelAgregarOU.add(fila3);
        
        JButton btnAgregarOU = new JButton("Agregar Bloque OU-IU");
        btnAgregarOU.setPreferredSize(new Dimension(200, 30));
        btnAgregarOU.addActionListener(e -> {
            try {
                int idOU = Integer.parseInt(txtIdOU.getText().trim());
                int idIUInicio = Integer.parseInt(txtIUInicio.getText().trim());
                int idIUFin = Integer.parseInt(txtIUFin.getText().trim());
                
                if (idIUFin < idIUInicio) {
                    JOptionPane.showMessageDialog(this, "ID IU Final debe ser mayor o igual al Inicial.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                ConfigOU config = new ConfigOU(idOU, idIUInicio, idIUFin);
                
                boolean yaExiste = listaOU.stream().anyMatch(c -> c.idOU == idOU);
                if (yaExiste) {
                    int opcion = JOptionPane.showConfirmDialog(this, 
                        "Ya existe una OU con ID " + idOU + ". Desea reemplazarla?", 
                        "Confirmar", JOptionPane.YES_NO_OPTION);
                    if (opcion != JOptionPane.YES_OPTION) return;
                    listaOU.removeIf(c -> c.idOU == idOU);
                }
                
                listaOU.add(config);
                actualizarListaOU();
                guardarConfiguracionSilencioso();
                agregarLog("OU-" + idOU + " agregada con IUs " + idIUInicio + "-" + idIUFin + " (" + config.getCantidadIU() + " unidades)");
                
                txtIdOU.setText("");
                txtIUInicio.setText("");
                txtIUFin.setText("");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Formato invalido. Use numeros.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        panelAgregarOU.add(btnAgregarOU);
        
        JButton btnEliminarOU = new JButton("Eliminar Seleccionada");
        btnEliminarOU.addActionListener(e -> {
            int idx = listaOUUI.getSelectedIndex();
            if (idx >= 0) {
                ConfigOU config = listaOU.get(idx);
                int opcion = JOptionPane.showConfirmDialog(this, 
                    "Eliminar OU-" + config.idOU + "?\nEsto eliminara el bloque y sus IUs.", "Confirmar", JOptionPane.YES_NO_OPTION);
                if (opcion == JOptionPane.YES_OPTION) {
                    listaOU.remove(idx);
                    actualizarListaOU();
                    guardarConfiguracionSilencioso();
                    agregarLog("OU-" + config.idOU + " eliminada. Configuracion guardada.");
                }
            } else {
                JOptionPane.showMessageDialog(this, "Seleccione un bloque OU-IU de la lista para eliminar.", "Sin seleccion", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        panelAgregarOU.add(btnEliminarOU);
        
        JButton btnGuardarOU = new JButton("Guardar Cambios");
        btnGuardarOU.addActionListener(e -> guardarConfiguracion());
        panelAgregarOU.add(btnGuardarOU);
        
        JButton btnRestaurarDefaults = new JButton("Restaurar Defaults");
        btnRestaurarDefaults.addActionListener(e -> {
            int opcion = JOptionPane.showConfirmDialog(this, 
                "Restaurar los 4 bloques OU-IU por defecto?\nEsto eliminara cualquier bloque personalizado.", 
                "Confirmar Restauracion", JOptionPane.YES_NO_OPTION);
            if (opcion == JOptionPane.YES_OPTION) {
                listaOU.clear();
                listaOU.addAll(ServidorModbusTest.getConfiguracionDefault());
                actualizarListaOU();
                guardarConfiguracionSilencioso();
                agregarLog("Configuracion restaurada a defaults (4 bloques).");
            }
        });
        panelAgregarOU.add(btnRestaurarDefaults);
        
        listaOUUI = new JList<>(listModelOU);
        listaOUUI.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollOU = new JScrollPane(listaOUUI);
        scrollOU.setPreferredSize(new Dimension(250, 150));
        
        panelOU.add(panelAgregarOU, BorderLayout.NORTH);
        panelOU.add(scrollOU, BorderLayout.CENTER);
        
        panelIzquierdo.add(panelOU, BorderLayout.CENTER);
        
        JPanel panelDerecho = new JPanel(new BorderLayout(10, 10));
        panelDerecho.setBorder(new TitledBorder("Variables BACnet a Leer"));
        
        String[] columnas = {"Index", "Tipo BACnet", "Instance"};
        tblModelVariables = new DefaultTableModel(columnas, 0);
        tblVariables = new JTable(tblModelVariables);
        tblVariables.setFont(new Font("Consolas", Font.PLAIN, 12));
        tblVariables.getColumnModel().getColumn(0).setPreferredWidth(60);
        tblVariables.getColumnModel().getColumn(1).setPreferredWidth(120);
        tblVariables.getColumnModel().getColumn(2).setPreferredWidth(80);
        
        JPanel panelBotonesVariables = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnAgregarVariable = new JButton("+ Agregar Variable");
        btnAgregarVariable.addActionListener(e -> agregarVariable());
        JButton btnEliminarVariable = new JButton("- Eliminar");
        btnEliminarVariable.addActionListener(e -> {
            int row = tblVariables.getSelectedRow();
            if (row >= 0) {
                listaVariables.remove(row);
                actualizarTablaVariables();
            }
        });
        panelBotonesVariables.add(btnAgregarVariable);
        panelBotonesVariables.add(btnEliminarVariable);
        
        JPanel panelTablaVar = new JPanel(new BorderLayout());
        panelTablaVar.add(new JScrollPane(tblVariables), BorderLayout.CENTER);
        panelTablaVar.add(panelBotonesVariables, BorderLayout.SOUTH);
        
        panelDerecho.add(panelTablaVar, BorderLayout.CENTER);
        
        panelSuperior.add(panelIzquierdo, BorderLayout.WEST);
        panelSuperior.add(panelDerecho, BorderLayout.CENTER);
        
        panel.add(panelSuperior, BorderLayout.NORTH);
        
        JPanel panelCentro = new JPanel(new BorderLayout(10, 10));
        panelCentro.setBorder(new TitledBorder("Log de Acciones"));
        txtLog = new JTextArea(8, 0);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtLog.setEditable(false);
        txtLog.setBackground(new Color(245, 245, 245));
        panelCentro.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        
        panel.add(panelCentro, BorderLayout.CENTER);
        
        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        JButton btnIniciar = new JButton("  INICIAR SERVIDOR  ");
        btnIniciar.setFont(new Font("Segoe UI", Font.BOLD, 16));
        btnIniciar.setPreferredSize(new Dimension(200, 45));
        btnIniciar.setBackground(new Color(76, 175, 80));
        btnIniciar.setForeground(Color.WHITE);
        btnIniciar.setFocusPainted(false);
        btnIniciar.addActionListener(e -> iniciarServidor());
        
        JButton btnDetener = new JButton("  DETENER  ");
        btnDetener.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnDetener.setBackground(new Color(244, 67, 54));
        btnDetener.setForeground(Color.WHITE);
        btnDetener.setFocusPainted(false);
        btnDetener.addActionListener(e -> detenerServidor());
        
        panelBotones.add(btnIniciar);
        panelBotones.add(btnDetener);
        
        panel.add(panelBotones, BorderLayout.SOUTH);
        
        actualizarTablaVariables();
        
        return panel;
    }
    
    private void agregarVariable() {
        String[] tipos = {"binaryInput", "binaryValue", "analogInput", "analogValue", "multiStateValue"};
        String tipo = (String) JOptionPane.showInputDialog(this, "Seleccione tipo:", "Nueva Variable", 
            JOptionPane.QUESTION_MESSAGE, null, tipos, tipos[0]);
        if (tipo == null) return;
        
        String instStr = JOptionPane.showInputDialog(this, "Instance BACnet:", "1");
        if (instStr == null) return;
        try {
            int instance = Integer.parseInt(instStr.trim());
            
            String idxStr = JOptionPane.showInputDialog(this, "Index Modbus (0-399):", "0");
            if (idxStr == null) return;
            int index = Integer.parseInt(idxStr.trim());
            
            if (index < 0 || index > 399) {
                JOptionPane.showMessageDialog(this, "Index debe estar entre 0 y 399.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            int duplicadoIdx = -1;
            for (int i = 0; i < listaVariables.size(); i++) {
                if (listaVariables.get(i).index == index) {
                    duplicadoIdx = i;
                    break;
                }
            }
            
            if (duplicadoIdx >= 0) {
                int opcion = JOptionPane.showConfirmDialog(this, 
                    "Ya existe variable en index " + index + " (" + listaVariables.get(duplicadoIdx).tipo + ":" + listaVariables.get(duplicadoIdx).instance + "). Desea reemplazarla?", 
                    "Index Duplicado", JOptionPane.YES_NO_OPTION);
                if (opcion != JOptionPane.YES_OPTION) return;
                listaVariables.remove(duplicadoIdx);
            }
            
            listaVariables.add(new VariableConfig(tipo, instance, index));
            Collections.sort(listaVariables, (a, b) -> Integer.compare(a.index, b.index));
            actualizarTablaVariables();
            agregarLog("Variable agregada: " + tipo + ":" + instance + " -> index " + index);
            
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Valor invalido.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void actualizarTablaVariables() {
        tblModelVariables.setRowCount(0);
        for (VariableConfig v : listaVariables) {
            tblModelVariables.addRow(new Object[]{v.index, v.tipo, v.instance});
        }
    }
    
    private void actualizarListaOU() {
        listModelOU.clear();
        for (ConfigOU config : listaOU) {
            listModelOU.addElement(String.format("OU: %d  |  IUs: %d - %d  |  (%d unidades)", 
                config.idOU, config.idIUInicio, config.idIUFin, config.getCantidadIU()));
        }
    }
    
    private void agregarLog(String mensaje) {
        String hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        txtLog.append("[" + hora + "] " + mensaje + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
    
    private boolean cargarConfiguracion() {
        File archivo = new File(ARCHIVO_CONFIG);
        if (!archivo.exists()) {
            agregarLog("Archivo de configuracion no encontrado. Usando defaults.");
            return false;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            listaOU.clear();
            String linea;
            int lineasLeidas = 0;
            
            while ((linea = reader.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) continue;
                
                String[] partes = linea.split(",");
                if (partes.length >= 3) {
                    try {
                        int idOU = Integer.parseInt(partes[0].trim());
                        int idIUInicio = Integer.parseInt(partes[1].trim());
                        int idIUFin = Integer.parseInt(partes[2].trim());
                        listaOU.add(new ConfigOU(idOU, idIUInicio, idIUFin));
                        lineasLeidas++;
                    } catch (NumberFormatException e) {
                        System.err.println("Linea invalida ignorada: " + linea);
                    }
                }
            }
            
            if (lineasLeidas > 0) {
                agregarLog("Configuracion cargada: " + lineasLeidas + " bloques OU-IU");
                return true;
            } else {
                agregarLog("Archivo de configuracion vacio. Usando defaults.");
                return false;
            }
        } catch (IOException e) {
            agregarLog("Error al cargar configuracion: " + e.getMessage());
            return false;
        }
    }
    
    private void guardarConfiguracion() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_CONFIG))) {
            writer.println("# Configuracion de Bloques OU-IU");
            writer.println("# Formato: idOU,idIUInicio,idIUFin");
            writer.println("# Ejemplo: 50100,50000,50007");
            writer.println();
            
            for (ConfigOU config : listaOU) {
                writer.println(config.idOU + "," + config.idIUInicio + "," + config.idIUFin);
            }
            
            agregarLog("Configuracion guardada: " + listaOU.size() + " bloques OU-IU");
            JOptionPane.showMessageDialog(this, 
                "Configuracion guardada exitosamente.\nArchivo: " + new File(ARCHIVO_CONFIG).getAbsolutePath(), 
                "Guardado", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            agregarLog("Error al guardar: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Error al guardar configuracion:\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private File getArchivoConfig() {
        return new File(ARCHIVO_CONFIG);
    }
    
    private void guardarConfiguracionSilencioso() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ARCHIVO_CONFIG))) {
            writer.println("# Configuracion de Bloques OU-IU");
            writer.println("# Formato: idOU,idIUInicio,idIUFin");
            writer.println();
            
            for (ConfigOU config : listaOU) {
                writer.println(config.idOU + "," + config.idIUInicio + "," + config.idIUFin);
            }
        } catch (IOException e) {
            System.err.println("Error al guardar configuracion silenciosamente: " + e.getMessage());
        }
    }
    
    private JPanel crearPanelVerDatos() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JPanel panelSuperior = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panelSuperior.add(new JLabel("Ultima actualizacion: "));
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
        if (!servidorActivo.get()) {
            lblHora.setText("SERVIDOR DETENIDO");
            lblHora.setForeground(Color.GRAY);
            textAreaDatos.setText("Inicie el servidor para ver los datos.\nConfigure los bloques OU-IU y las variables en la pestana Configuracion.");
            textAreaDatos.setForeground(Color.GRAY);
            return;
        }
        
        lblHora.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        lblHora.setForeground(new Color(0, 100, 0));
        textAreaDatos.setForeground(Color.BLACK);
        
        Map<Integer, Integer> valores = ServidorModbusTest.obtenerValoresRegistros();
        
        StringBuilder sb = new StringBuilder();
        
        if (listaOU.isEmpty()) {
            sb.append("No hay bloques OU-IU configurados.\n");
            sb.append("Vaya a Configuracion para agregar bloques.\n");
        } else {
            for (int ouIdx = 0; ouIdx < listaOU.size(); ouIdx++) {
                ConfigOU config = listaOU.get(ouIdx);
                int base = ouIdx * 400;
                
                sb.append("\nOU-").append(ouIdx).append(" ID: ").append(config.idOU).append("\n");
                sb.append("------------------------------------------------\n");
                
                sb.append("  Vars Propias OU\n");
                for (int i = 0; i < 10; i++) {
                    int d = base + i;
                    int valor = valores.containsKey(d) ? valores.get(d) : 0;
                    sb.append(String.format("  %-3d [%5d] %s\n", i, valor, ServidorModbusTest.getNombreVariable(i)));
                }
                
                int cantidadIU = config.idIUFin - config.idIUInicio + 1;
                for (int idxIU = 0; idxIU < cantidadIU; idxIU++) {
                    int idIU = config.idIUInicio + idxIU;
                    sb.append("\n  IU-").append(idxIU).append(" ID: ").append(idIU).append("\n");
                    sb.append("  ------------------------------------------------\n");
                    for (int i = 0; i < 20; i++) {
                        int d = base + 10 + idxIU * 20 + i;
                        int valor = valores.containsKey(d) ? valores.get(d) : 0;
                        sb.append(String.format("  %-3d [%5d] %s\n", i, valor, ServidorModbusTest.getNombreVariable(i)));
                    }
                }
            }
        }
        
        textAreaDatos.setText(sb.toString());
        textAreaDatos.setCaretPosition(0);
    }
    
    private void iniciarServidor() {
        if (servidorActivo.get()) {
            JOptionPane.showMessageDialog(this, "El servidor ya esta activo.", "Informacion", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        try {
            int puerto = Integer.parseInt(txtPuertoModbus.getText().trim());
            int puertoBacnet = Integer.parseInt(txtPuertoBacnet.getText().trim());
            int idLocal = Integer.parseInt(txtIdLocal.getText().trim());
            
            if (puerto < 1 || puerto > 65535 || puertoBacnet < 1 || puertoBacnet > 65535) {
                JOptionPane.showMessageDialog(this, "Puerto invalido.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            agregarLog("Iniciando servidor...");
            agregarLog("Puerto Modbus: " + puerto);
            agregarLog("Puerto BACnet: " + puertoBacnet);
            agregarLog("OU configuradas: " + listaOU.size());
            agregarLog("Variables: " + listaVariables.size());
            
            threadServidor = new Thread(() -> {
                try {
                    ServidorModbusTest.ejecutarServidor(puerto, puertoBacnet, idLocal, listaOU, listaVariables);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        agregarLog("ERROR: " + ex.getMessage());
                        servidorActivo.set(false);
                    });
                }
            });
            threadServidor.start();
            
            servidorActivo.set(true);
            
            agregarLog("Servidor iniciado correctamente.");
            JOptionPane.showMessageDialog(this, "Servidor iniciado correctamente.", "Exito", JOptionPane.INFORMATION_MESSAGE);
            
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Parametros invalidos.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void detenerServidor() {
        if (!servidorActivo.get()) {
            JOptionPane.showMessageDialog(this, "El servidor no esta activo.", "Informacion", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        int opcion = JOptionPane.showConfirmDialog(this, "Detener el servidor?", "Confirmar", JOptionPane.YES_NO_OPTION);
        if (opcion != JOptionPane.YES_OPTION) return;
        
        try {
            ServidorModbusTest.detenerServidor();
            servidorActivo.set(false);
            agregarLog("Servidor detenido.");
            actualizarPanelDatos(new JLabel());
            JOptionPane.showMessageDialog(this, "Servidor detenido.", "Informacion", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            agregarLog("Error al detener: " + ex.getMessage());
        }
    }
    
    static class ConfigOU {
        int idOU;
        int idIUInicio;
        int idIUFin;
        ConfigOU(int idOU, int idIUInicio, int idIUFin) {
            this.idOU = idOU;
            this.idIUInicio = idIUInicio;
            this.idIUFin = idIUFin;
        }
        int getCantidadIU() {
            return idIUFin - idIUInicio + 1;
        }
    }
    
    static class VariableConfig {
        String tipo;
        int instance;
        int index;
        VariableConfig(String tipo, int instance, int index) {
            this.tipo = tipo;
            this.instance = instance;
            this.index = index;
        }
    }
}
