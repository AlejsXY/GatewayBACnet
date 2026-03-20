package com.mycompany.servidormodbusTest;

import net.wimpi.modbus.ModbusCoupler;
import net.wimpi.modbus.procimg.*;
import net.wimpi.modbus.net.ModbusTCPListener;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.RemoteObject;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.util.RequestUtils;
import com.serotonin.bacnet4j.util.DiscoveryUtils;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.event.DeviceEventListener;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.type.primitive.Boolean;

import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

public class ServidorModbusTest {

    // ==================== CONFIGURACIÓN GENERAL ====================
    private static final int PUERTO_MODBUS = 503;
    private static final int UNIT_ID = 1;
    private static final int INTERVALO_HEARTBEAT = 5000;
    private static final int PUERTO_BACNET_CLIENTE = 47808;
    private static final int ID_DISPOSITIVO_BACNET = 12345;
    private static final int TIEMPO_ESPERA_DESCUBRIMIENTO = 10000;
    private static final int INTERVALO_LECTURA_BACNET = 10000;

    // ==================== CONFIGURACIÓN MODBUS ====================
    private static final int NUM_OUTDOOR = 7;                     // Número de Outdoor Units
    private static final int REGISTROS_POR_OU = 400;              // Cada OU ocupa 400 registros (10 propias + hasta 19 IU * 20)
    private static final int VARS_PROPIAS_OU = 10;                // Registros propios de la OU
    private static final int VARS_POR_INDOOR = 20;                // Registros por Indoor Unit
    private static final int MAX_INDOOR_POR_OU = (REGISTROS_POR_OU - VARS_PROPIAS_OU) / VARS_POR_INDOOR; // = 19

    // Permisos (false = RW, true = RO)
    private static final boolean[] PERMISOS_PROPIAS_OU = new boolean[VARS_PROPIAS_OU];
    private static final boolean[] PERMISOS_INDOOR = new boolean[VARS_POR_INDOOR];

    static {
        Arrays.fill(PERMISOS_PROPIAS_OU, false);
        Arrays.fill(PERMISOS_INDOOR, false);
        // Índices de solo lectura (true)
        PERMISOS_INDOOR[0] = true;  // binaryInput:1
        PERMISOS_INDOOR[1] = true;  // binaryInput:2
        PERMISOS_INDOOR[2] = true;  // analogInput:1
        PERMISOS_INDOOR[3] = true;  // analogInput:2
        PERMISOS_INDOOR[4] = true;  // analogInput:3
        PERMISOS_INDOOR[5] = true;  // analogInput:8
        PERMISOS_INDOOR[6] = true;  // analogInput:9
        PERMISOS_INDOOR[7] = true;  // analogInput:10
        PERMISOS_INDOOR[10] = true; // analogInput:7
        // Los índices 8,9,11,12,13,14 son RW (false por defecto)
    }

    // ==================== MAPEO DE OBJETOS BACNET ====================
    private static final String[] NOMBRES_VARIABLES = {
        "BI-1", "BI-2", "AI-1", "AI-2", "AI-3", "AI-8", "AI-9", "AI-10",
        "AV-1", "AV-6", "AI-7", "BV-1", "BV-4", "MSV-1", "MSV-2",
        "Var15", "Var16", "Var17", "Var18", "Var19"
    };
    
    private static final List<BacnetObjectMapping> MAPEO_OBJETOS = Arrays.asList(
        new BacnetObjectMapping(ObjectType.binaryInput, 1, 0),
        new BacnetObjectMapping(ObjectType.binaryInput, 2, 1),
        new BacnetObjectMapping(ObjectType.analogInput, 1, 2),
        new BacnetObjectMapping(ObjectType.analogInput, 2, 3),
        new BacnetObjectMapping(ObjectType.analogInput, 3, 4),
        new BacnetObjectMapping(ObjectType.analogInput, 8, 5),
        new BacnetObjectMapping(ObjectType.analogInput, 9, 6),
        new BacnetObjectMapping(ObjectType.analogInput, 10, 7),
        new BacnetObjectMapping(ObjectType.analogValue, 1, 8),
        new BacnetObjectMapping(ObjectType.analogValue, 6, 9),
        new BacnetObjectMapping(ObjectType.analogInput, 7, 10),
        new BacnetObjectMapping(ObjectType.binaryValue, 1, 11),
        new BacnetObjectMapping(ObjectType.binaryValue, 4, 12),
        new BacnetObjectMapping(ObjectType.multiStateValue, 1, 13),
        new BacnetObjectMapping(ObjectType.multiStateValue, 2, 14)
    );

    static class BacnetObjectMapping {
        ObjectType type;
        int instance;
        int variableIndex;

        BacnetObjectMapping(ObjectType type, int instance, int variableIndex) {
            this.type = type;
            this.instance = instance;
            this.variableIndex = variableIndex;
        }
    }
    
    public static String getNombreVariable(int index) {
        if (index >= 0 && index < NOMBRES_VARIABLES.length) {
            return NOMBRES_VARIABLES[index];
        }
        return "Var" + index;
    }
    
    public static List<GatewayGUI.ConfigOU> getConfiguracionDefault() {
        List<GatewayGUI.ConfigOU> defaults = new ArrayList<>();
        defaults.add(new GatewayGUI.ConfigOU(50100, 50000, 50007));
        defaults.add(new GatewayGUI.ConfigOU(51112, 51001, 51009));
        defaults.add(new GatewayGUI.ConfigOU(52120, 52001, 52015));
        defaults.add(new GatewayGUI.ConfigOU(53124, 53001, 53009));
        return defaults;
    }

    // ==================== REGISTRO CONTROLADO ====================
    static class RegistroControlado implements Register {
        private final int direccion;
        private final String nombre;
        private final boolean soloLectura;
        private int valor;

        public RegistroControlado(int direccion, String nombre, boolean soloLectura, int valorInicial) {
            this.direccion = direccion;
            this.nombre = nombre;
            this.soloLectura = soloLectura;
            this.valor = valorInicial;
        }

        public boolean isSoloLectura() { return soloLectura; }
        public String getNombre() { return nombre; }
        @Override public int getValue() { return valor; }

        @Override
        public void setValue(int v) {
            if (soloLectura) {
                System.err.println("  [Modbus] Intento de escritura en solo lectura: " + direccion + " (" + nombre + ")");
                return;
            }
            int anterior = this.valor;
            this.valor = v;
            System.out.println("  [Modbus] Escritura en " + direccion + " (" + nombre + "): " + anterior + " → " + v);
            BACnetClienteIntegrado.escribirEnDispositivo(direccion, v);
        }

        @Override public byte[] toBytes() {
            return new byte[]{(byte)(valor >> 8), (byte)(valor & 0xFF)};
        }
        @Override public void setValue(short s) { setValue((int) s); }
        @Override public void setValue(byte[] bytes) {
            if (bytes.length >= 2) {
                setValue(((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
            }
        }
        @Override public int toUnsignedShort() { return valor & 0xFFFF; }
        @Override public short toShort() { return (short) valor; }
    }

    // ==================== REGISTRO NO DEFINIDO ====================
    private static final Register REGISTRO_NO_DEFINIDO = new Register() {
        @Override public int getValue() { return 0; }
        @Override public void setValue(int v) { }
        @Override public byte[] toBytes() { return new byte[]{0,0}; }
        @Override public void setValue(short s) { }
        @Override public void setValue(byte[] bytes) { }
        @Override public int toUnsignedShort() { return 0; }
        @Override public short toShort() { return 0; }
    };

    // ==================== MAPA DE REGISTROS MODBUS ====================
    private static final Map<Integer, RegistroControlado> registros = new HashMap<>();

    // ==================== PROCESSIMAGE PERSONALIZADO ====================
    static class MapeoProcessImage implements ProcessImage {
        @Override
        public Register getRegister(int ref) {
            RegistroControlado r = registros.get(ref);
            return r != null ? r : REGISTRO_NO_DEFINIDO;
        }

        @Override
        public Register[] getRegisterRange(int ref, int count) {
            Register[] regs = new Register[count];
            for (int i = 0; i < count; i++) {
                RegistroControlado r = registros.get(ref + i);
                regs[i] = r != null ? r : REGISTRO_NO_DEFINIDO;
            }
            return regs;
        }

        @Override public DigitalOut getDigitalOut(int ref) { return null; }
        @Override public DigitalOut[] getDigitalOutRange(int ref, int count) { return null; }
        @Override public DigitalIn getDigitalIn(int ref) { return null; }
        @Override public DigitalIn[] getDigitalInRange(int ref, int count) { return null; }
        @Override public InputRegister getInputRegister(int ref) { return null; }
        @Override public InputRegister[] getInputRegisterRange(int ref, int count) { return null; }
        @Override public int getDigitalOutCount() { return 0; }
        @Override public int getDigitalInCount() { return 0; }
        @Override public int getInputRegisterCount() { return 0; }
        @Override public int getRegisterCount() { return registros.size(); }
    }

    // ==================== CLIENTE BACNET INTEGRADO ====================
    static class BACnetClienteIntegrado {
        private static LocalDevice localDevice;
        private static final List<RemoteDevice> dispositivos = new CopyOnWriteArrayList<>();
        private static Thread monitorThread;
        private static volatile boolean running = true;
        private static int intervaloMs = INTERVALO_LECTURA_BACNET;

        private static final Map<RemoteDevice, Map<ObjectIdentifier, Integer>> mapaObjetoADireccion = new ConcurrentHashMap<>();
        private static final Map<Integer, MapeoEscritura> mapaDireccionAObjeto = new ConcurrentHashMap<>();

        static class MapeoEscritura {
            RemoteDevice dispositivo;
            ObjectIdentifier oid;
            MapeoEscritura(RemoteDevice d, ObjectIdentifier o) { dispositivo = d; oid = o; }
        }

        // Método para obtener OU e IU a partir del deviceId según la convención
        private static int[] obtenerOuIuDesdeDeviceId(int deviceId) {
            if (deviceId >= 50000 && deviceId <= 50007) {
                return new int[]{1, deviceId - 50000 + 1}; // OU1, IU 1..8
            } else if (deviceId >= 51001 && deviceId <= 51009) {
                return new int[]{2, deviceId - 51000}; // 51001->1, ..., 51009->9
            } else if (deviceId >= 52001 && deviceId <= 52015) {
                return new int[]{3, deviceId - 52000}; // 52001->1, ..., 52015->15
            } else if (deviceId >= 53001 && deviceId <= 53009) {
                return new int[]{4, deviceId - 53000}; // 53001->1, ..., 53009->9
            } else if (deviceId == 50100) {
                return new int[]{1, 0}; // OU1 propia
            } else if (deviceId == 51112) {
                return new int[]{2, 0}; // OU2 propia
            } else if (deviceId == 52120) {
                return new int[]{3, 0}; // OU3 propia
            } else if (deviceId == 53124) {
                return new int[]{4, 0}; // OU4 propia
            }
            return null;
        }

        public static void iniciar() throws Exception {
        String ipLocal = obtenerIpLocalStatic();
            String broadcast = ipLocal.substring(0, ipLocal.lastIndexOf('.')) + ".255";

            System.out.println("IP local detectada: " + ipLocal);
            System.out.println("Broadcast: " + broadcast);

            IpNetwork network = new IpNetworkBuilder()
                    .withLocalBindAddress(ipLocal)
                    .withBroadcast(broadcast, 0)
                    .withPort(PUERTO_BACNET_CLIENTE)
                    .build();

            DefaultTransport transport = new DefaultTransport(network);
            localDevice = new LocalDevice(ID_DISPOSITIVO_BACNET, transport);
            localDevice.initialize();

            localDevice.getEventHandler().addListener(new DeviceEventListener() {
                @Override
                public void iAmReceived(RemoteDevice rd) {
                    System.out.println("  [BACnet] I-Am recibido de dispositivo " + rd.getInstanceNumber() + " desde " + rd.getAddress());
                    if (!dispositivos.contains(rd)) {
                        dispositivos.add(rd);
                        System.out.println("  [BACnet] Dispositivo añadido: " + rd.getInstanceNumber());
                    }
                }

                @Override public void iHaveReceived(RemoteDevice rd, RemoteObject ro) { }
                @Override public void covNotificationReceived(com.serotonin.bacnet4j.type.primitive.UnsignedInteger subscriberProcessId, ObjectIdentifier initiatingDevice, ObjectIdentifier monitoredObject, com.serotonin.bacnet4j.type.primitive.UnsignedInteger timeRemaining, com.serotonin.bacnet4j.type.constructed.SequenceOf<PropertyValue> listOfValues) { }
                @Override public void eventNotificationReceived(com.serotonin.bacnet4j.type.primitive.UnsignedInteger processIdentifier, ObjectIdentifier initiatingDevice, ObjectIdentifier eventObjectIdentifier, com.serotonin.bacnet4j.type.constructed.TimeStamp timeStamp, com.serotonin.bacnet4j.type.primitive.UnsignedInteger notificationClass, com.serotonin.bacnet4j.type.primitive.UnsignedInteger priority, com.serotonin.bacnet4j.type.enumerated.EventType eventType, com.serotonin.bacnet4j.type.primitive.CharacterString messageText, com.serotonin.bacnet4j.type.enumerated.NotifyType notifyType, com.serotonin.bacnet4j.type.primitive.Boolean ackRequired, com.serotonin.bacnet4j.type.enumerated.EventState fromState, com.serotonin.bacnet4j.type.enumerated.EventState toState, com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters notificationParameters) { }
                @Override public void textMessageReceived(ObjectIdentifier textMessageSourceDevice, com.serotonin.bacnet4j.type.constructed.Choice messageClass, com.serotonin.bacnet4j.type.enumerated.MessagePriority messagePriority, com.serotonin.bacnet4j.type.primitive.CharacterString message) { }
                @Override public void synchronizeTime(com.serotonin.bacnet4j.type.constructed.Address from, com.serotonin.bacnet4j.type.constructed.DateTime dateTime, boolean utc) { }
                @Override public void requestReceived(com.serotonin.bacnet4j.type.constructed.Address from, com.serotonin.bacnet4j.service.Service service) { }
                @Override public boolean allowPropertyWrite(com.serotonin.bacnet4j.type.constructed.Address address, com.serotonin.bacnet4j.obj.BACnetObject obj, PropertyValue pv) { return true; }
                @Override public void propertyWritten(com.serotonin.bacnet4j.type.constructed.Address address, com.serotonin.bacnet4j.obj.BACnetObject obj, PropertyValue pv) { }
                @Override public void listenerException(Throwable e) { }
            });

            System.out.println("Cliente BACnet inicializado.");

            localDevice.sendGlobalBroadcast(new WhoIsRequest(null, null));
            System.out.println("Who-Is broadcast enviado.");

            System.out.println("Esperando " + TIEMPO_ESPERA_DESCUBRIMIENTO/1000 + " segundos para recibir I-Am...");
            Thread.sleep(TIEMPO_ESPERA_DESCUBRIMIENTO);

            for (RemoteDevice dispositivo : dispositivos) {
                int deviceId = dispositivo.getInstanceNumber();
                int[] ouIu = obtenerOuIuDesdeDeviceId(deviceId);
                if (ouIu == null) {
                    System.out.println("Dispositivo " + deviceId + " no está en el mapeo, se ignora.");
                    continue;
                }
                int ou = ouIu[0];
                int iu = ouIu[1];

                if (ou < 1 || ou > NUM_OUTDOOR) {
                    System.err.println("OU " + ou + " fuera de rango para dispositivo " + deviceId);
                    continue;
                }
                if (iu < 0 || iu > MAX_INDOOR_POR_OU) {
                    System.err.println("IU " + iu + " fuera de rango (máx " + MAX_INDOOR_POR_OU + ") para dispositivo " + deviceId + ". Se ignora.");
                    continue;
                }

                try {
                    DiscoveryUtils.getExtendedDeviceInformation(localDevice, dispositivo);
                    Encodable encodable = RequestUtils.readProperty(localDevice, dispositivo, dispositivo.getObjectIdentifier(), PropertyIdentifier.objectList, null);
                    if (encodable instanceof com.serotonin.bacnet4j.type.constructed.SequenceOf) {
                        com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList = (com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>) encodable;

                        int base;
                        if (iu == 0) {
                            base = (ou - 1) * REGISTROS_POR_OU;
                        } else {
                            base = (ou - 1) * REGISTROS_POR_OU + VARS_PROPIAS_OU + (iu - 1) * VARS_POR_INDOOR;
                        }
                        System.out.println("Dispositivo " + deviceId + " (OU" + ou + ", IU" + iu + ") base = " + base);

                        Map<ObjectIdentifier, Integer> mapaDispositivo = new ConcurrentHashMap<>();
                        for (ObjectIdentifier oid : objectList) {
                            for (BacnetObjectMapping mapping : MAPEO_OBJETOS) {
                                if (oid.getObjectType().equals(mapping.type) && oid.getInstanceNumber() == mapping.instance) {
                                    int direccion = base + mapping.variableIndex;
                                    mapaDispositivo.put(oid, direccion);
                                    System.out.println("  [MAPEO] " + oid + " → dirección " + direccion + " (base " + base + " + idx " + mapping.variableIndex + ")");
                                    break;
                                }
                            }
                        }
                        if (!mapaDispositivo.isEmpty()) {
                            mapaObjetoADireccion.put(dispositivo, mapaDispositivo);
                            for (Map.Entry<ObjectIdentifier, Integer> entry : mapaDispositivo.entrySet()) {
                                mapaDireccionAObjeto.put(entry.getValue(), new MapeoEscritura(dispositivo, entry.getKey()));
                            }
                            System.out.println("Dispositivo " + deviceId + " (OU" + ou + (iu==0 ? " propia" : " IU"+iu) + ") mapeado a base " + base + " con " + mapaDispositivo.size() + " objetos");
                        } else {
                            System.out.println("Dispositivo " + deviceId + " no tiene objetos de interés");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error obteniendo información del dispositivo " + deviceId + ": " + e.getMessage());
                }
            }

            System.out.println("Mapa de objetos BACnet construido con " + mapaDireccionAObjeto.size() + " entradas.");

            monitorThread = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(intervaloMs);
                        leerTodosLosDispositivos();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        private static void leerTodosLosDispositivos() {
            for (RemoteDevice dispositivo : dispositivos) {
                Map<ObjectIdentifier, Integer> mapaDispositivo = mapaObjetoADireccion.get(dispositivo);
                if (mapaDispositivo == null) continue;
                for (Map.Entry<ObjectIdentifier, Integer> entry : mapaDispositivo.entrySet()) {
                    ObjectIdentifier oid = entry.getKey();
                    int direccionModbus = entry.getValue();
                    try {
                        Encodable valor = RequestUtils.readProperty(localDevice, dispositivo, oid, PropertyIdentifier.presentValue, null);
                        int valorInt = 0;
                        if (valor instanceof Real) {
                            valorInt = Math.round(((Real) valor).floatValue());
                        } else if (valor instanceof Boolean) {
                            valorInt = ((Boolean) valor).booleanValue() ? 1 : 0;
                        } else if (valor instanceof BinaryPV) {
                            valorInt = valor.equals(BinaryPV.active) ? 1 : 0;
                        } else if (valor instanceof UnsignedInteger) {
                            valorInt = ((UnsignedInteger) valor).intValue();
                        } else {
                            System.err.println("  [BACnet] Tipo no manejado: " + valor.getClass().getSimpleName() + " para " + oid);
                            continue;
                        }
                        actualizarDesdeBACnet(direccionModbus, valorInt);
                        System.out.println("  [BACnet] Leído " + oid + " = " + valorInt + " → registro " + direccionModbus);
                    } catch (BACnetException e) {
                        System.err.println("  [BACnet] Error leyendo " + oid + " del dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
                    }
                }
            }
        }

        public static void escribirEnDispositivo(int direccionModbus, int valor) {
            MapeoEscritura m = mapaDireccionAObjeto.get(direccionModbus);
            if (m == null) {
                System.err.println("  [BACnet] No hay objeto mapeado para dirección Modbus " + direccionModbus);
                return;
            }
            try {
                ObjectType tipo = m.oid.getObjectType();
                Encodable valorEncodable;
                if (tipo.equals(ObjectType.analogValue) || tipo.equals(ObjectType.analogOutput)) {
                    valorEncodable = new Real(valor);
                } else if (tipo.equals(ObjectType.binaryValue) || tipo.equals(ObjectType.binaryOutput)) {
                    valorEncodable = (valor != 0) ? BinaryPV.active : BinaryPV.inactive;
                } else if (tipo.equals(ObjectType.multiStateValue) || tipo.equals(ObjectType.multiStateOutput)) {
                    valorEncodable = new UnsignedInteger(valor);
                } else {
                    System.err.println("  [BACnet] Tipo de objeto no soportado para escritura: " + tipo);
                    return;
                }
                WritePropertyRequest request = new WritePropertyRequest(
                        m.oid,
                        PropertyIdentifier.presentValue,
                        null,
                        valorEncodable,
                        null
                );
                localDevice.send(m.dispositivo.getAddress(), request);
                System.out.println("  [BACnet] Escrito en " + m.oid + " valor " + valor + " en dispositivo " + m.dispositivo.getInstanceNumber());
                Thread.sleep(100);
            } catch (Exception e) {
                System.err.println("  [BACnet] Error escribiendo en dispositivo: " + e.getMessage());
            }
        }

        public static void detener() {
            running = false;
            if (monitorThread != null) {
                monitorThread.interrupt();
            }
            if (localDevice != null) {
                localDevice.terminate();
            }
        }

        private static String obtenerIpLocal() {
            try {
                Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface net = interfaces.nextElement();
                    if (net.isUp() && !net.isLoopback() && !net.isVirtual() && !net.isPointToPoint()) {
                        Enumeration<java.net.InetAddress> addresses = net.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            java.net.InetAddress addr = addresses.nextElement();
                            if (addr instanceof java.net.Inet4Address && !addr.isLinkLocalAddress()) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
            return "0.0.0.0";
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================
    private static void mostrarConfiguracion() {
        System.out.println("==========================================");
        System.out.println("  GATEWAY BACnet → Modbus");
        System.out.println("==========================================");
        System.out.println("Configuración Modbus:");
        System.out.println("  • Puerto: " + PUERTO_MODBUS);
        System.out.println("  • Unit ID: " + UNIT_ID);
        System.out.println("  • Outdoor Units: " + NUM_OUTDOOR);
        System.out.println("  • Registros por OU: " + REGISTROS_POR_OU);
        System.out.println("  • Variables propias OU: " + VARS_PROPIAS_OU);
        System.out.println("  • Variables por Indoor: " + VARS_POR_INDOOR);
        System.out.println("  • Máx Indoor por OU: " + MAX_INDOOR_POR_OU);
        System.out.println("  • Rango total: 0 - " + (NUM_OUTDOOR * REGISTROS_POR_OU - 1));
        System.out.println("Configuración BACnet:");
        System.out.println("  • Puerto cliente: " + PUERTO_BACNET_CLIENTE);
        System.out.println("  • ID dispositivo local: " + ID_DISPOSITIVO_BACNET);
        System.out.println("  • Tiempo descubrimiento: " + TIEMPO_ESPERA_DESCUBRIMIENTO/1000 + " s");
        System.out.println("  • Intervalo lectura: " + INTERVALO_LECTURA_BACNET/1000 + " s");
        System.out.println("------------------------------------------");
        mostrarResumenPermisos();
        System.out.println("Mapeo de objetos BACnet:");
        for (BacnetObjectMapping m : MAPEO_OBJETOS) {
            System.out.println("  • " + m.type + ":" + m.instance + " → índice " + m.variableIndex);
        }
        System.out.println("------------------------------------------");
    }

    private static void mostrarResumenPermisos() {
        System.out.println("Permisos por variable:");
        System.out.print("  Propias OU: ");
        for (int i = 0; i < VARS_PROPIAS_OU; i++) {
            System.out.print((i > 0 ? ", " : "") + (PERMISOS_PROPIAS_OU[i] ? "RO" : "RW"));
        }
        System.out.println();
        System.out.print("  Indoor:      ");
        for (int i = 0; i < VARS_POR_INDOOR; i++) {
            System.out.print((i > 0 ? ", " : "") + (PERMISOS_INDOOR[i] ? "RO" : "RW"));
        }
        System.out.println();
    }

    private static void generarRegistros() {
        registros.clear();
        String[] varsPropias = new String[VARS_PROPIAS_OU];
        String[] varsIndoor = new String[VARS_POR_INDOOR];
        for (int i = 0; i < VARS_PROPIAS_OU; i++) varsPropias[i] = "Propia" + i;
        for (int i = 0; i < VARS_POR_INDOOR; i++) varsIndoor[i] = "Var" + i;

        for (int ou = 1; ou <= NUM_OUTDOOR; ou++) {
            int base = (ou - 1) * REGISTROS_POR_OU;
            int offset = 0;
            // Variables propias de la OU (10 registros)
            for (int v = 0; v < VARS_PROPIAS_OU; v++) {
                int dir = base + offset++;
                String nombre = "OU" + ou + " " + varsPropias[v];
                registros.put(dir, new RegistroControlado(dir, nombre, PERMISOS_PROPIAS_OU[v], 0));
            }
            // Indoor Units (hasta MAX_INDOOR_POR_OU, cada una con VARS_POR_INDOOR registros)
            for (int iu = 1; iu <= MAX_INDOOR_POR_OU; iu++) {
                for (int v = 0; v < VARS_POR_INDOOR; v++) {
                    int dir = base + offset++;
                    String nombre = "OU" + ou + " IU" + iu + " " + varsIndoor[v];
                    registros.put(dir, new RegistroControlado(dir, nombre, PERMISOS_INDOOR[v], 0));
                }
            }
        }
    }

    // ==================== API PARA BACNET ====================
        public static void actualizarDesdeBACnet(int direccion, int nuevoValor) {
            RegistroControlado r = registros.get(direccion);
            if (r != null) {
                r.valor = nuevoValor; // Actualiza siempre (la fuente es BACnet)
                System.out.println("  [BACnet] Registro " + direccion + " actualizado a " + nuevoValor);
            } else {
                System.err.println("  [BACnet] Dirección " + direccion + " no existe");
            }
        }

    // ==================== API PARA GUI ====================
    public static Map<Integer, Integer> obtenerValoresRegistros() {
        Map<Integer, Integer> valores = new HashMap<>();
        synchronized (registros) {
            for (Map.Entry<Integer, RegistroControlado> entry : registros.entrySet()) {
                valores.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        return valores;
    }

    public static Map<Integer, String> obtenerNombresRegistros() {
        Map<Integer, String> nombres = new HashMap<>();
        synchronized (registros) {
            for (Map.Entry<Integer, RegistroControlado> entry : registros.entrySet()) {
                nombres.put(entry.getKey(), entry.getValue().getNombre());
            }
        }
        return nombres;
    }

    public static void detenerServidor() {
        try {
            BACnetClienteIntegrado.detener();
        } catch (Exception e) { }
        System.out.println("Servidor detenido por GUI.");
    }

    public static void ejecutarServidor(int puerto, int puertoBacnet, int idLocal, 
            List<GatewayGUI.ConfigOU> listaOU, List<GatewayGUI.VariableConfig> listaVars) throws Exception {
        
        System.out.println("==========================================");
        System.out.println("  GATEWAY BACnet → Modbus (GUI)");
        System.out.println("==========================================");
        
        int numOU = listaOU.size();
        int registrosPorOU = 400;
        
        System.out.println("Puerto Modbus: " + puerto);
        System.out.println("Puerto BACnet: " + puertoBacnet);
        System.out.println("Outdoor Units: " + numOU);
        System.out.println("Registros por OU: " + registrosPorOU);
        System.out.println("Variables configuradas: " + listaVars.size());
        
        registros.clear();
        
        int ouIndex = 0;
        for (GatewayGUI.ConfigOU config : listaOU) {
            int base = ouIndex * registrosPorOU;
            
            for (int i = 0; i < 10; i++) {
                int dir = base + i;
                String nombre = "OU-" + config.idOU + " " + getNombreVariable(i);
                registros.put(dir, new RegistroControlado(dir, nombre, false, 0));
            }
            
            int cantidadIU = config.idIUFin - config.idIUInicio + 1;
            for (int idxIU = 0; idxIU < cantidadIU; idxIU++) {
                int deviceIdIU = config.idIUInicio + idxIU;
                for (int i = 0; i < 20; i++) {
                    int dir = base + 10 + idxIU * 20 + i;
                    String nombre = "IU-" + deviceIdIU + " " + getNombreVariable(i);
                    registros.put(dir, new RegistroControlado(dir, nombre, false, 0));
                }
            }
            
            System.out.println("OU-" + config.idOU + " mapeada a base " + base + " con IUs " + config.idIUInicio + "-" + config.idIUFin);
            ouIndex++;
        }
        
        MapeoProcessImage processImage = new MapeoProcessImage();
        ModbusCoupler.getReference().setProcessImage(processImage);
        ModbusCoupler.getReference().setUnitID(1);

        ModbusTCPListener listener = new ModbusTCPListener(10);
        listener.setAddress(InetAddress.getByName("0.0.0.0"));
        listener.setPort(puerto);
        listener.start();
        System.out.println("Servidor Modbus activo en puerto " + puerto);

        final int finalPuertoBacnet = puertoBacnet;
        final int finalIdLocal = idLocal;
        final List<GatewayGUI.ConfigOU> finalListaOU = listaOU;
        final List<GatewayGUI.VariableConfig> finalListaVars = listaVars;

        new Thread(() -> {
            try {
                iniciarClienteBACnetGUI(finalPuertoBacnet, finalIdLocal, finalListaOU, finalListaVars);
            } catch (Exception e) {
                System.err.println("Error BACnet: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(5000);
            String hora = LocalTime.now().format(formatter);
            System.out.println("[" + hora + "] Heartbeat");
        }
    }

    private static String obtenerIpLocalStatic() {
        try {
            Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface net = interfaces.nextElement();
                if (net.isUp() && !net.isLoopback() && !net.isVirtual() && !net.isPointToPoint()) {
                    Enumeration<java.net.InetAddress> addresses = net.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address && !addr.isLinkLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return "0.0.0.0";
    }

    private static ObjectType tipoBacnetDesdeString(String nombre) {
        switch (nombre) {
            case "binaryInput": return ObjectType.binaryInput;
            case "binaryValue": return ObjectType.binaryValue;
            case "analogInput": return ObjectType.analogInput;
            case "analogValue": return ObjectType.analogValue;
            case "multiStateValue": return ObjectType.multiStateValue;
            default: return null;
        }
    }

    private static void iniciarClienteBACnetGUI(int puertoBacnet, int idLocal, 
            List<GatewayGUI.ConfigOU> listaOU, List<GatewayGUI.VariableConfig> listaVars) throws Exception {
        
        String ipLocal = obtenerIpLocalStatic();
        String broadcast = ipLocal.substring(0, ipLocal.lastIndexOf('.')) + ".255";

        System.out.println("IP local: " + ipLocal);
        System.out.println("Broadcast: " + broadcast);

        IpNetwork network = new IpNetworkBuilder()
                .withLocalBindAddress(ipLocal)
                .withBroadcast(broadcast, 0)
                .withPort(puertoBacnet)
                .build();

        DefaultTransport transport = new DefaultTransport(network);
        LocalDevice localDevice = new LocalDevice(idLocal, transport);
        localDevice.initialize();

        final List<RemoteDevice> dispositivos = new CopyOnWriteArrayList<>();

        localDevice.getEventHandler().addListener(new DeviceEventListener() {
            @Override
            public void iAmReceived(RemoteDevice rd) {
                System.out.println("  [BACnet] I-Am: " + rd.getInstanceNumber() + " desde " + rd.getAddress());
                if (!dispositivos.contains(rd)) {
                    dispositivos.add(rd);
                }
            }
            @Override public void iHaveReceived(RemoteDevice rd, RemoteObject ro) { }
            @Override public void covNotificationReceived(com.serotonin.bacnet4j.type.primitive.UnsignedInteger subscriberProcessId, ObjectIdentifier initiatingDevice, ObjectIdentifier monitoredObject, com.serotonin.bacnet4j.type.primitive.UnsignedInteger timeRemaining, com.serotonin.bacnet4j.type.constructed.SequenceOf<PropertyValue> listOfValues) { }
            @Override public void eventNotificationReceived(com.serotonin.bacnet4j.type.primitive.UnsignedInteger processIdentifier, ObjectIdentifier initiatingDevice, ObjectIdentifier eventObjectIdentifier, com.serotonin.bacnet4j.type.constructed.TimeStamp timeStamp, com.serotonin.bacnet4j.type.primitive.UnsignedInteger notificationClass, com.serotonin.bacnet4j.type.primitive.UnsignedInteger priority, com.serotonin.bacnet4j.type.enumerated.EventType eventType, com.serotonin.bacnet4j.type.primitive.CharacterString messageText, com.serotonin.bacnet4j.type.enumerated.NotifyType notifyType, com.serotonin.bacnet4j.type.primitive.Boolean ackRequired, com.serotonin.bacnet4j.type.enumerated.EventState fromState, com.serotonin.bacnet4j.type.enumerated.EventState toState, com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters notificationParameters) { }
            @Override public void textMessageReceived(ObjectIdentifier textMessageSourceDevice, com.serotonin.bacnet4j.type.constructed.Choice messageClass, com.serotonin.bacnet4j.type.enumerated.MessagePriority messagePriority, com.serotonin.bacnet4j.type.primitive.CharacterString message) { }
            @Override public void synchronizeTime(com.serotonin.bacnet4j.type.constructed.Address from, com.serotonin.bacnet4j.type.constructed.DateTime dateTime, boolean utc) { }
            @Override public void requestReceived(com.serotonin.bacnet4j.type.constructed.Address from, com.serotonin.bacnet4j.service.Service service) { }
            @Override public boolean allowPropertyWrite(com.serotonin.bacnet4j.type.constructed.Address address, com.serotonin.bacnet4j.obj.BACnetObject obj, PropertyValue pv) { return true; }
            @Override public void propertyWritten(com.serotonin.bacnet4j.type.constructed.Address address, com.serotonin.bacnet4j.obj.BACnetObject obj, PropertyValue pv) { }
            @Override public void listenerException(Throwable e) { }
        });

        localDevice.sendGlobalBroadcast(new WhoIsRequest(null, null));
        System.out.println("Who-Is enviado.");

        Thread.sleep(5000);

        Map<Integer, GatewayGUI.ConfigOU> mapaDeviceIdOU = new HashMap<>();
        for (GatewayGUI.ConfigOU config : listaOU) {
            mapaDeviceIdOU.put(config.idOU, config);
        }

        int ouIndex = 0;
        for (GatewayGUI.ConfigOU config : listaOU) {
            int base = ouIndex * 400;
            
            int deviceIdPropio = config.idOU;
            Optional<RemoteDevice> dispositivoPropio = dispositivos.stream()
                .filter(d -> d.getInstanceNumber() == deviceIdPropio)
                .findFirst();
            
            if (dispositivoPropio.isPresent()) {
                System.out.println("Cacheando OU-" + config.idOU + " (deviceId " + deviceIdPropio + ")");
                cachearDispositivo(localDevice, dispositivoPropio.get(), base, listaVars);
            } else {
                System.out.println("OU-" + config.idOU + " (deviceId " + deviceIdPropio + ") no encontrada");
            }
            
            int cantidadIU = config.idIUFin - config.idIUInicio + 1;
            for (int idxIU = 0; idxIU < cantidadIU; idxIU++) {
                int deviceIdIU = config.idIUInicio + idxIU;
                int baseIU = base + 10 + idxIU * 20;
                
                Optional<RemoteDevice> dispositivoIU = dispositivos.stream()
                    .filter(d -> d.getInstanceNumber() == deviceIdIU)
                    .findFirst();
                
                if (dispositivoIU.isPresent()) {
                    System.out.println("Cacheando IU (deviceId " + deviceIdIU + ")");
                    cachearDispositivo(localDevice, dispositivoIU.get(), baseIU, listaVars);
                } else {
                    System.out.println("IU (deviceId " + deviceIdIU + ") no encontrada");
                }
            }
            ouIndex++;
        }

        System.out.println("Iniciando lectura continua...");
        while (true) {
            Thread.sleep(10000);
            leerTodosLosDispositivosGUI(localDevice, dispositivos, listaVars, listaOU);
        }
    }

    private static void mapearDispositivo(LocalDevice localDevice, RemoteDevice dispositivo, int base, List<GatewayGUI.VariableConfig> listaVars) {
        try {
            DiscoveryUtils.getExtendedDeviceInformation(localDevice, dispositivo);
            Encodable encodable = RequestUtils.readProperty(localDevice, dispositivo, dispositivo.getObjectIdentifier(), PropertyIdentifier.objectList, null);
            
            if (encodable instanceof com.serotonin.bacnet4j.type.constructed.SequenceOf) {
                com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList = 
                    (com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>) encodable;

                for (ObjectIdentifier oid : objectList) {
                    for (GatewayGUI.VariableConfig var : listaVars) {
                        ObjectType tipo = tipoBacnetDesdeString(var.tipo);
                        if (tipo != null && oid.getObjectType().equals(tipo) && oid.getInstanceNumber() == var.instance) {
                            int direccion = base + var.index;
                            System.out.println("  [MAPEO] " + oid + " → reg " + direccion);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error mapeando dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
        }
    }

    private static final Map<RemoteDevice, com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>> cacheObjectList = new ConcurrentHashMap<>();
    private static final Map<RemoteDevice, Integer> cacheDeviceToBase = new ConcurrentHashMap<>();
    private static final Map<RemoteDevice, List<ObjectIdentifier>> cacheDeviceToOids = new ConcurrentHashMap<>();

    private static void cachearDispositivo(LocalDevice localDevice, RemoteDevice dispositivo, int base, List<GatewayGUI.VariableConfig> listaVars) {
        try {
            DiscoveryUtils.getExtendedDeviceInformation(localDevice, dispositivo);
            Encodable encodable = RequestUtils.readProperty(localDevice, dispositivo, dispositivo.getObjectIdentifier(), PropertyIdentifier.objectList, null);
            
            if (encodable instanceof com.serotonin.bacnet4j.type.constructed.SequenceOf) {
                com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList = 
                    (com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>) encodable;

                cacheObjectList.put(dispositivo, objectList);
                cacheDeviceToBase.put(dispositivo, base);
                
                List<ObjectIdentifier> oidsRelevantes = new ArrayList<>();
                for (ObjectIdentifier oid : objectList) {
                    for (GatewayGUI.VariableConfig var : listaVars) {
                        ObjectType tipo = tipoBacnetDesdeString(var.tipo);
                        if (tipo != null && oid.getObjectType().equals(tipo) && oid.getInstanceNumber() == var.instance) {
                            int direccion = base + var.index;
                            System.out.println("  [CACHE] " + oid + " → reg " + direccion);
                            oidsRelevantes.add(oid);
                            break;
                        }
                    }
                }
                cacheDeviceToOids.put(dispositivo, oidsRelevantes);
                System.out.println("  [CACHE] Dispositivo " + dispositivo.getInstanceNumber() + " cacheado con " + oidsRelevantes.size() + " objetos relevantes");
            }
        } catch (Exception e) {
            System.err.println("Error cacheando dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
        }
    }

    private static void leerTodosLosDispositivosOptimizado(LocalDevice localDevice, List<GatewayGUI.VariableConfig> listaVars) {
        for (RemoteDevice dispositivo : cacheDeviceToBase.keySet()) {
            Integer base = cacheDeviceToBase.get(dispositivo);
            List<ObjectIdentifier> oids = cacheDeviceToOids.get(dispositivo);
            if (base == null || oids == null || oids.isEmpty()) continue;
            
            try {
                for (ObjectIdentifier oid : oids) {
                    for (GatewayGUI.VariableConfig var : listaVars) {
                        ObjectType tipo = tipoBacnetDesdeString(var.tipo);
                        if (tipo != null && oid.getObjectType().equals(tipo) && oid.getInstanceNumber() == var.instance) {
                            int direccion = base + var.index;
                            try {
                                Encodable valor = RequestUtils.readProperty(localDevice, dispositivo, oid, PropertyIdentifier.presentValue, null);
                                int valorInt = convertirValorABacnet(valor);
                                actualizarDesdeBACnet(direccion, valorInt);
                            } catch (BACnetException e) {
                                System.err.println("  [BACnet] Error leyendo " + oid + ": " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error en lectura optimizada dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
            }
        }
    }

    private static int convertirValorABacnet(Encodable valor) {
        if (valor instanceof Real) {
            return Math.round(((Real) valor).floatValue());
        } else if (valor instanceof Boolean) {
            return ((Boolean) valor).booleanValue() ? 1 : 0;
        } else if (valor instanceof BinaryPV) {
            return valor.equals(BinaryPV.active) ? 1 : 0;
        } else if (valor instanceof UnsignedInteger) {
            return ((UnsignedInteger) valor).intValue();
        }
        return 0;
    }

    private static void leerTodosLosDispositivosRPM(LocalDevice localDevice, List<GatewayGUI.VariableConfig> listaVars) {
        for (RemoteDevice dispositivo : cacheDeviceToBase.keySet()) {
            Integer base = cacheDeviceToBase.get(dispositivo);
            List<ObjectIdentifier> oids = cacheDeviceToOids.get(dispositivo);
            if (base == null || oids == null || oids.isEmpty()) continue;
            
            try {
                for (ObjectIdentifier oid : oids) {
                    for (GatewayGUI.VariableConfig var : listaVars) {
                        ObjectType tipo = tipoBacnetDesdeString(var.tipo);
                        if (tipo != null && oid.getObjectType().equals(tipo) && oid.getInstanceNumber() == var.instance) {
                            int direccion = base + var.index;
                            try {
                                Encodable valor = RequestUtils.readProperty(localDevice, dispositivo, oid, PropertyIdentifier.presentValue, null);
                                int valorInt = convertirValorABacnet(valor);
                                actualizarDesdeBACnet(direccion, valorInt);
                            } catch (BACnetException e) {
                                System.err.println("  [BACnet] Error leyendo " + oid + ": " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error en lectura optimizada dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
            }
        }
    }

    private static void leerTodosLosDispositivosGUI(LocalDevice localDevice, List<RemoteDevice> dispositivos, 
            List<GatewayGUI.VariableConfig> listaVars, List<GatewayGUI.ConfigOU> listaOU) {
        for (RemoteDevice dispositivo : dispositivos) {
            try {
                Encodable encodable = RequestUtils.readProperty(localDevice, dispositivo, dispositivo.getObjectIdentifier(), PropertyIdentifier.objectList, null);
                
                if (encodable instanceof com.serotonin.bacnet4j.type.constructed.SequenceOf) {
                    com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList = 
                        (com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>) encodable;

                    int ouIndex = 0;
                    for (GatewayGUI.ConfigOU config : listaOU) {
                        int base = ouIndex * 400;
                        
                        if (dispositivo.getInstanceNumber() == config.idOU) {
                            leerObjetosDelista(localDevice, dispositivo, objectList, base, listaVars);
                        }
                        
                        int cantidadIU = config.idIUFin - config.idIUInicio + 1;
                        for (int idxIU = 0; idxIU < cantidadIU; idxIU++) {
                            int deviceIdIU = config.idIUInicio + idxIU;
                            int baseIU = base + 10 + idxIU * 20;
                            if (dispositivo.getInstanceNumber() == deviceIdIU) {
                                leerObjetosDelista(localDevice, dispositivo, objectList, baseIU, listaVars);
                            }
                        }
                        ouIndex++;
                    }
                }
            } catch (Exception e) {
                System.err.println("Error leyendo dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
            }
        }
    }

    private static void leerObjetosDelista(LocalDevice localDevice, RemoteDevice dispositivo, 
            com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList, 
            int base, List<GatewayGUI.VariableConfig> listaVars) {
        
        for (ObjectIdentifier oid : objectList) {
            for (GatewayGUI.VariableConfig var : listaVars) {
                ObjectType tipo = tipoBacnetDesdeString(var.tipo);
                if (tipo != null && oid.getObjectType().equals(tipo) && oid.getInstanceNumber() == var.instance) {
                    int direccion = base + var.index;
                    try {
                        Encodable valor = RequestUtils.readProperty(localDevice, dispositivo, oid, PropertyIdentifier.presentValue, null);
                        int valorInt = 0;
                        if (valor instanceof Real) {
                            valorInt = Math.round(((Real) valor).floatValue());
                        } else if (valor instanceof Boolean) {
                            valorInt = ((Boolean) valor).booleanValue() ? 1 : 0;
                        } else if (valor instanceof BinaryPV) {
                            valorInt = valor.equals(BinaryPV.active) ? 1 : 0;
                        } else if (valor instanceof UnsignedInteger) {
                            valorInt = ((UnsignedInteger) valor).intValue();
                        }
                        actualizarDesdeBACnet(direccion, valorInt);
                    } catch (Exception e) {
                        // Silencioso
                    }
                    break;
                }
            }
        }
    }

    // ==================== MAIN ====================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GatewayGUI());
    }
}