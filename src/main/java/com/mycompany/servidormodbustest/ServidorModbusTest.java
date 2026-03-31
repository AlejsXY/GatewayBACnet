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
import com.serotonin.bacnet4j.event.DeviceEventListener;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import java.net.Inet4Address;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;

public class ServidorModbusTest {

    // ==================== CONSTANTES GENERALES ====================
    private static final int UNIT_ID = 1;
    private static final int INTERVALO_HEARTBEAT = 5000;
    // Las constantes de tiempos ya no son fijas, se reciben como parámetros
    // private static final int TIEMPO_ESPERA_DESCUBRIMIENTO = 2000;
    // private static final int INTERVALO_LECTURA_BACNET = 2000;

    // ==================== REGISTRO CONTROLADO ====================
    static class RegistroControlado implements Register {
        private final int direccion;
        private final String nombre;
        private final boolean soloLectura;
        private volatile int valor;

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
            boolean success = BACnetClienteIntegrado.escribirEnDispositivo(direccion, v);
            if(success){
                int anterior = this.valor;
                this.valor = v;
                System.out.println("  [Modbus] Escritura en " + direccion + " (" + nombre + "): " + anterior + " → " + v);
            }else{
                System.err.println("  [Modbus] Escritura en " + direccion + " (" + nombre + ") falló en BACnet, valor no actualizado.");
            }
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
        private static volatile LocalDevice localDevice;
        private static final List<RemoteDevice> dispositivos = new CopyOnWriteArrayList<>();

        // Mapas de caché
        private static final Map<RemoteDevice, Integer> cacheDeviceToBase = new ConcurrentHashMap<>();
        private static final Map<RemoteDevice, List<ObjectIdentifier>> cacheDeviceToOids = new ConcurrentHashMap<>();
        
        // Ahora guardamos la dirección directamente con cada OID por dispositivo
        private static final Map<RemoteDevice, List<Integer>> cacheDeviceToAddresses = new ConcurrentHashMap<>();
        
        // Mapa de dirección Modbus a objeto (para escritura)
        private static final Map<Integer, MapeoEscritura> mapaDireccionAObjeto = new ConcurrentHashMap<>();

        static class MapeoEscritura {
            RemoteDevice dispositivo;
            ObjectIdentifier oid;
            MapeoEscritura(RemoteDevice d, ObjectIdentifier o) { dispositivo = d; oid = o; }
        }

        static void reiniciarCaches() {
            cacheDeviceToBase.clear();
            cacheDeviceToOids.clear();
            cacheDeviceToAddresses.clear();
            mapaDireccionAObjeto.clear();
            dispositivos.clear();
            System.out.println("  [BACnet] Cachés estáticas reiniciadas.");
        }

        private static void cachearDispositivo(LocalDevice localDevice, RemoteDevice dispositivo, int base, List<BacnetObjectMapping> objetos) {
            try {
                DiscoveryUtils.getExtendedDeviceInformation(localDevice, dispositivo);
                Encodable encodable = RequestUtils.readProperty(localDevice, dispositivo, dispositivo.getObjectIdentifier(), PropertyIdentifier.objectList, null);
                
                if (encodable instanceof com.serotonin.bacnet4j.type.constructed.SequenceOf) {
                    com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier> objectList = 
                        (com.serotonin.bacnet4j.type.constructed.SequenceOf<ObjectIdentifier>) encodable;

                    cacheDeviceToBase.put(dispositivo, base);
                    
                    System.out.println("  [DEBUG] Objetos en dispositivo " + dispositivo.getInstanceNumber() + ":");
                    for (ObjectIdentifier oid : objectList) {
                        System.out.println("    - " + oid.getObjectType() + " " + oid.getInstanceNumber());
                    }
                    System.out.println("  [DEBUG] Buscando variables: " + objetos);
                    
                    List<ObjectIdentifier> oidsPrevios = cacheDeviceToOids.getOrDefault(dispositivo, new ArrayList<>());
                    List<Integer> dirPrevias = cacheDeviceToAddresses.getOrDefault(dispositivo, new ArrayList<>());
                    List<ObjectIdentifier> oidsRelevantes = new ArrayList<>(oidsPrevios);
                    List<Integer> direccionesRelevantes = new ArrayList<>(dirPrevias);
                    for (ObjectIdentifier oid : objectList) {
                        for (BacnetObjectMapping m : objetos) {
                            if (oid.getObjectType().equals(m.type) && oid.getInstanceNumber() == m.instance) {
                                int direccion = base + m.variableIndex;
                                System.out.println("  [CACHE] " + oid + " → reg " + direccion + " (dispositivo: " + dispositivo.getInstanceNumber() + ")");
                                oidsRelevantes.add(oid);
                                direccionesRelevantes.add(direccion);
                                mapaDireccionAObjeto.put(direccion, new MapeoEscritura(dispositivo, oid));
                                break;
                            }
                        }
                    }
                    cacheDeviceToOids.put(dispositivo, oidsRelevantes);
                    cacheDeviceToAddresses.put(dispositivo, direccionesRelevantes);
                    System.out.println("  [CACHE] Dispositivo " + dispositivo.getInstanceNumber() + " cacheado con " + oidsRelevantes.size() + " objetos relevantes");
                }
            } catch (Exception e) {
                System.err.println("Error cacheando dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
            }
        }

        public static boolean escribirEnDispositivo(int direccionModbus, int valor) {
            MapeoEscritura m = mapaDireccionAObjeto.get(direccionModbus);
            if (m == null) {
                System.err.println("  [BACnet] No hay objeto mapeado para dirección Modbus " + direccionModbus);
                return false;
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
                    return false;
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
                return true;
            } catch (Exception e) {
                System.err.println("  [BACnet] Error escribiendo en dispositivo: " + e.getMessage());
                return false;
            }
        }

        public static void detener() {
            if (localDevice != null) {
                localDevice.terminate();
            }
        }
    }

    // ==================== CLASE AUXILIAR PARA MAPEO DE OBJETOS ====================
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

    static class MapeoDispositivo {
        int deviceId;
        int base;
        List<BacnetObjectMapping> objetos;
        boolean esEscritura;
        MapeoDispositivo(int deviceId, int base, List<BacnetObjectMapping> objetos, boolean esEscritura) {
            this.deviceId = deviceId;
            this.base = base;
            this.objetos = objetos;
            this.esEscritura = esEscritura;
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================
    private static ObjectType tipoBacnetDesdeString(String nombre) {
        switch (nombre) {
            case "binaryInput": return ObjectType.binaryInput;
            case "binaryValue": return ObjectType.binaryValue;
            case "analogInput": return ObjectType.analogInput;
            case "analogValue": return ObjectType.analogValue;
            case "multiStateValue": return ObjectType.multiStateValue;
            case "multiStateInput": return ObjectType.multiStateInput;
            default: return null;
        }
    }

    private static List<BacnetObjectMapping> convertirVarsToMapping(List<GatewayGUI.VariableConfig> vars) {
        List<BacnetObjectMapping> mapeos = new ArrayList<>();
        for (int i = 0; i < vars.size(); i++) {
            GatewayGUI.VariableConfig vc = vars.get(i);
            ObjectType tipo = tipoBacnetDesdeString(vc.tipo);
            if (tipo != null) {
                mapeos.add(new BacnetObjectMapping(tipo, vc.instance, i));
            }
        }
        return mapeos;
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

    // ==================== API PARA BACNET ====================
    public static void actualizarDesdeBACnet(int direccion, int nuevoValor) {
        synchronized (registros) {
            RegistroControlado r = registros.get(direccion);
            if (r != null) {
                int anterior = r.valor;
                r.valor = nuevoValor;
                System.out.println("  [BACnet] Registro " + direccion + " = " + nuevoValor + " (antes: " + anterior + ", nombre: " + r.getNombre() + ")");
            } else {
                System.err.println("  [BACnet] Dirección " + direccion + " no existe");
            }
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

    // ==================== MÉTODO PRINCIPAL DEL SERVIDOR ====================
    // AHORA RECIBE LOS PARÁMETROS DE TIEMPOS DESDE LA GUI
    public static void ejecutarServidor(int puerto, int puertoBacnet, int idLocal,
            List<GatewayGUI.ConfigBloqueOU> listaBloques,
            GatewayGUI.VariableDefinitions varDefs,
            String ipLocal,
            int tiempoDescubrimientoMs,      // nuevo
            int intervaloLecturaMs) throws Exception {  // nuevo
        
        System.out.println("==========================================");
        System.out.println("  GATEWAY BACnet → Modbus (GUI) - Variables globales");
        System.out.println("==========================================");
        
        registros.clear();
        BACnetClienteIntegrado.reiniciarCaches();
        
        List<MapeoDispositivo> mapeosLectura = new ArrayList<>();
        List<MapeoDispositivo> mapeosEscritura = new ArrayList<>();
        List<Integer> listaIdIUs = new ArrayList<>();
        
        int offsetGlobal = 0;
        
        for (GatewayGUI.ConfigBloqueOU bloque : listaBloques) {
            int cantidadIU = bloque.getCantidadIU();
            int tamLectura = 6 + cantidadIU * 2;
            int baseLectura = offsetGlobal;
            
            // Variables propias de OU
            for (int i = 0; i < 6; i++) {
                GatewayGUI.VariableConfig var = varDefs.ouVars.get(i);
                int dir = baseLectura + i;
                String nombre = "OU-" + bloque.idOU + " " + var.tipo + ":" + var.instance;
                boolean soloLectura = var.tipo.endsWith("Input");
                registros.put(dir, new RegistroControlado(dir, nombre, soloLectura, 0));
            }
            mapeosLectura.add(new MapeoDispositivo(bloque.idOU, baseLectura, convertirVarsToMapping(varDefs.ouVars), false));
            
            // Lectura por IU
            int baseLecturaIU = baseLectura + 6;
            for (int iuIdx = 0; iuIdx < cantidadIU; iuIdx++) {
                int idIU = bloque.idIUInicio + iuIdx;
                listaIdIUs.add(idIU);
                for (int j = 0; j < 2; j++) {
                    GatewayGUI.VariableConfig var = varDefs.iuReadVars.get(j);
                    int dir = baseLecturaIU + iuIdx*2 + j;
                    String nombre = "IU-" + idIU + " " + var.tipo + ":" + var.instance;
                    boolean soloLectura = var.tipo.endsWith("Input");
                    registros.put(dir, new RegistroControlado(dir, nombre, soloLectura, 0));
                }
                mapeosLectura.add(new MapeoDispositivo(idIU, baseLecturaIU + iuIdx*2, convertirVarsToMapping(varDefs.iuReadVars), false));
            }
            offsetGlobal += tamLectura;
        }
        
        // Bloque de escritura
        int baseEscrituraGlobal = offsetGlobal;
        int iuGlobal = 0;
        for (GatewayGUI.ConfigBloqueOU bloque : listaBloques) {
            int cantidadIU = bloque.getCantidadIU();
            for (int iuIdx = 0; iuIdx < cantidadIU; iuIdx++) {
                int idIU = listaIdIUs.get(iuGlobal);
                int baseIUescritura = baseEscrituraGlobal + iuGlobal * 2;
                for (int j = 0; j < 2; j++) {
                    GatewayGUI.VariableConfig var = varDefs.iuWriteVars.get(j);
                    int dir = baseIUescritura + j;
                    String nombre = "IU-" + idIU + " " + var.tipo + ":" + var.instance;
                    registros.put(dir, new RegistroControlado(dir, nombre, false, 0));
                }
                mapeosEscritura.add(new MapeoDispositivo(idIU, baseIUescritura, convertirVarsToMapping(varDefs.iuWriteVars), true));
                iuGlobal++;
            }
        }
        
        // Configurar servidor Modbus
        MapeoProcessImage processImage = new MapeoProcessImage();
        ModbusCoupler.getReference().setProcessImage(processImage);
        ModbusCoupler.getReference().setUnitID(UNIT_ID);
        
        ModbusTCPListener listener = new ModbusTCPListener(10);
        listener.setAddress(InetAddress.getByName("0.0.0.0"));
        listener.setPort(puerto);
        listener.start();
        System.out.println("Servidor Modbus activo en puerto " + puerto);
        
        // Iniciar cliente BACnet en hilo separado, pasando los tiempos
        final int tiempoDescubrimiento = tiempoDescubrimientoMs;
        final int intervaloLectura = intervaloLecturaMs;
        new Thread(() -> {
            try {
                iniciarClienteBACnetGUI(puertoBacnet, idLocal, mapeosLectura, mapeosEscritura, ipLocal,
                                        tiempoDescubrimiento, intervaloLectura);
            } catch (Exception e) {
                System.err.println("Error BACnet: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
        
        // Heartbeat
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(INTERVALO_HEARTBEAT);
            System.out.println("[" + LocalTime.now().format(formatter) + "] Heartbeat");
        }
    }

    // ==================== CLIENTE BACNET (DESCUBRIMIENTO Y LECTURA) ====================
    // RECIBE LOS TIEMPOS COMO PARÁMETROS
    private static void iniciarClienteBACnetGUI(int puertoBacnet, int idLocal,
            List<MapeoDispositivo> mapeosLectura, List<MapeoDispositivo> mapeosEscritura,
            String ipLocal, int tiempoDescubrimientoMs, int intervaloLecturaMs) throws Exception {
        
        String broadcast = obtenerBroadcastDesdeIp(ipLocal);
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
        BACnetClienteIntegrado.localDevice = localDevice;
        System.out.println("  [DEBUG] BACnetClienteIntegrado.localDevice asignado");
        
        List<RemoteDevice> dispositivos = new CopyOnWriteArrayList<>();
        
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
        System.out.println("Who-Is broadcast enviado.");
        
        // Usar el tiempo de descubrimiento recibido
        Thread.sleep(tiempoDescubrimientoMs);
        System.out.println("I-Am responses recibidas: " + dispositivos.size());
        
        // Mapear dispositivos encontrados
        for (RemoteDevice dispositivo : dispositivos) {
            int deviceId = dispositivo.getInstanceNumber();
            System.out.println("[DEBUG] Procesando dispositivo BACnet instance: " + deviceId);
            MapeoDispositivo mp = mapeosLectura.stream().filter(m -> m.deviceId == deviceId).findFirst().orElse(null);
            if (mp != null) {
                System.out.println("[DEBUG] -> Coincide con mapeo LECTURA, base=" + mp.base + ", deviceId=" + mp.deviceId);
                BACnetClienteIntegrado.cachearDispositivo(localDevice, dispositivo, mp.base, mp.objetos);
            }
            mp = mapeosEscritura.stream().filter(m -> m.deviceId == deviceId).findFirst().orElse(null);
            if (mp != null) {
                System.out.println("[DEBUG] -> Coincide con mapeo ESCRITURA, base=" + mp.base + ", deviceId=" + mp.deviceId);
                BACnetClienteIntegrado.cachearDispositivo(localDevice, dispositivo, mp.base, mp.objetos);
            }
            if (mp == null && mapeosLectura.stream().noneMatch(m -> m.deviceId == deviceId)) {
                System.out.println("Dispositivo " + deviceId + " no coincide con ningún mapeo, se ignora.");
            }
        }
        
        System.out.println("[DEBUG] Mapa de direcciones a objeto para ESCRITURA:");
        for (Integer dir : BACnetClienteIntegrado.mapaDireccionAObjeto.keySet()) {
            System.out.println("  Reg " + dir + " -> " + BACnetClienteIntegrado.mapaDireccionAObjeto.get(dir).oid);
        }
        
        // Bucle de lectura continua con el intervalo configurable
        Map<String, Integer> contadorErroresLectura = new ConcurrentHashMap<>();
        while (true) {
            Thread.sleep(intervaloLecturaMs);
            leerTodosLosDispositivosGUI(localDevice, contadorErroresLectura);
        }
    }
    
    private static void leerTodosLosDispositivosGUI(LocalDevice localDevice,
            Map<String, Integer> contadorErroresLectura) {
        System.out.println("  [DEBUG] Leyendo dispositivos cacheados. Total: " + BACnetClienteIntegrado.cacheDeviceToBase.size());
        
        // Obtener lista de IDs en orden para debug
        List<Integer> deviceIds = new ArrayList<>();
        for (RemoteDevice d : BACnetClienteIntegrado.cacheDeviceToBase.keySet()) {
            deviceIds.add(d.getInstanceNumber());
        }
        System.out.println("  [DEBUG] Orden de lectura de dispositivos: " + deviceIds);
        
        for (RemoteDevice dispositivo : BACnetClienteIntegrado.cacheDeviceToBase.keySet()) {
            List<ObjectIdentifier> oids = BACnetClienteIntegrado.cacheDeviceToOids.get(dispositivo);
            List<Integer> direcciones = BACnetClienteIntegrado.cacheDeviceToAddresses.get(dispositivo);
            if (oids == null || oids.isEmpty() || direcciones == null) continue;
            
            System.out.println("  [DEBUG] Leyendo dispositivo " + dispositivo.getInstanceNumber() + ", OIDs: " + oids.size() + ", direcciones: " + direcciones);
            
            try {
                for (int i = 0; i < oids.size(); i++) {
                    ObjectIdentifier oid = oids.get(i);
                    Integer direccion = direcciones.get(i);
                    if (direccion == null) continue;
                    String clave = dispositivo.getInstanceNumber() + "_" + oid.toString();
                    try {
                        Encodable valor = RequestUtils.readProperty(localDevice, dispositivo, oid, PropertyIdentifier.presentValue, null);
                        int valorInt = convertirValorABacnet(valor);
                        actualizarDesdeBACnet(direccion, valorInt);
                        contadorErroresLectura.remove(clave);
                    } catch (BACnetException e) {
                        int errores = contadorErroresLectura.getOrDefault(clave, 0) + 1;
                        contadorErroresLectura.put(clave, errores);
                        if (errores == 3) {
                            System.err.println("  [AVISO] Objeto " + oid + " del dispositivo " + dispositivo.getInstanceNumber() +
                                " lleva 3 lecturas fallidas consecutivas. Verifique la configuración o conectividad.");
                        } else if (errores > 3 && errores % 10 == 0) {
                            System.err.println("  [AVISO] Objeto " + oid + " continúa fallando (" + errores + " intentos fallidos).");
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error en lectura dispositivo " + dispositivo.getInstanceNumber() + ": " + e.getMessage());
            }
        }
    }
    
    // ==================== OBTENCIÓN DE BROADCAST REAL ====================
    private static String obtenerBroadcastDesdeIp(String ipLocal) throws Exception {
        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        while (nets.hasMoreElements()) {
            NetworkInterface net = nets.nextElement();
            for (InterfaceAddress addr : net.getInterfaceAddresses()) {
                InetAddress inet = addr.getAddress();
                if (inet instanceof Inet4Address && inet.getHostAddress().equals(ipLocal)) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null) {
                        return broadcast.getHostAddress();
                    }
                }
            }
        }
        throw new Exception("No se pudo obtener el broadcast para la IP " + ipLocal);
    }
    
    // ==================== MAIN ====================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GatewayGUI());
    }
}