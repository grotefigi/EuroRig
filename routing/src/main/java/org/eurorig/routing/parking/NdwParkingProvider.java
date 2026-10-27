package org.eurorig.routing.parking;
import java.io.*;import java.time.*;import java.util.*;import javax.xml.parsers.*;import org.xml.sax.*;import org.xml.sax.ext.DefaultHandler2;

/** NDW DATEX II v2 location / v3 status adapter. Namespace-aware, bounded, no external entities. */
public final class NdwParkingProvider implements ParkingProvider {
    private static final String STATIC="http://datex2.eu/schema/2/2_0",DYNAMIC="http://datex2.eu/schema/3/parking";
    public String id(){return "ndw-nl";}public String attribution(){return "NDW · Netherlands Open Data";}
    public String tableUrl(){return "https://opendata.ndw.nu/Truckparking_Parking_Table.xml";}
    public String statusUrl(){return "https://opendata.ndw.nu/Truckparking_Parking_Status.xml";}
    public Parking.Table table(byte[] xml)throws IOException{
        Document parsed=parse(xml,false);ArrayList<Parking.Site> sites=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Node record:parsed.records){String id=record.attr("id");if(id.isEmpty()||!ids.add(id))throw new IOException("Missing or duplicate parking id");Node location=record.child("parkingLocation");double[] point=point(location);if(point==null)continue;
            LinkedHashMap<String,Parking.Group> groups=new LinkedHashMap<>();for(Node group:record.children("groupOfParkingSpaces")){
                String index=group.attr("groupIndex");Node basics=group.child("parkingSpaceBasics");Node only=basics.child("onlyAssignedParking"),among=basics.child("assignedParkingAmongOthers");Node vehicles=only!=Node.EMPTY?only:among;
                List<Node> types=vehicles.descendants("vehicleType");boolean trucks=types.size()==1&&"lorry".equals(types.get(0).text());boolean generic=vehicles.descendants("loadType2").isEmpty()&&vehicles.descendants("vehicleType2").isEmpty();
                if(index.isEmpty()||groups.containsKey(index))throw new IOException("Duplicate or missing parking group");groups.put(index,new Parking.Group(index,integer(basics.child("parkingNumberOfSpaces").text(),-1),trucks,only!=Node.EMPTY,generic));
            }
            ArrayList<Parking.Entrance> entrances=new ArrayList<>();for(Node access:record.children("parkingAccess")){
                if(!Arrays.asList("vehicleEntrance","vehicleEntranceAndExit").contains(access.child("accessCategory").text()))continue;
                double[] entry=point(access.child("location"));if(entry==null)continue;Node road=access.child("primaryRoad");entrances.add(new Parking.Entrance(access.attr("id"),entry[0],entry[1],value(road.child("roadIdentifier")),value(road.child("roadDestination"))));
            }
            sites.add(new Parking.Site(id(),id,record.attr("version"),value(record.child("parkingName")),point[0],point[1],record.child("parkingReservation").text(),groups,entrances));
        }
        if(parsed.tableId.isEmpty()||sites.isEmpty())throw new IOException("NDW location table is empty or unidentified");
        return new Parking.Table(parsed.tableId,parsed.tableVersion,parsed.informationStatus,parsed.publication,sites);
    }
    public Parking.Status status(byte[] xml)throws IOException{
        Document parsed=parse(xml,true);LinkedHashMap<String,Parking.Observation> observations=new LinkedHashMap<>();
        for(Node record:parsed.records){Node reference=record.child("parkingRecordReference");String id=reference.attr("id");if(id.isEmpty()||observations.containsKey(id))throw new IOException("Missing or duplicate occupancy id");
            Map<String,Parking.Count> counts=new LinkedHashMap<>();for(Node group:record.children("groupOfParkingSpacesStatus")){
                String index=group.attr("groupIndex");if(index.isEmpty()||counts.containsKey(index))throw new IOException("Missing or duplicate occupancy group");Node count=group.child("groupOfParkingSpacesStatus");
                counts.put(index,new Parking.Count(nullable(count.child("parkingNumberOfVacantSpaces")),nullable(count.child("parkingNumberOfOccupiedSpaces")),reliable(group)));
            }
            observations.put(id,new Parking.Observation(id,reference.attr("version"),record.child("parkingSiteStatus").text(),timestamp(record.child("parkingStatusOriginTime").text()),reliable(record),counts));
        }
        if(parsed.tableId.isEmpty()||observations.isEmpty())throw new IOException("NDW status snapshot is empty or unidentified");
        return new Parking.Status(parsed.tableId,parsed.tableVersion,parsed.publication,observations);
    }
    private static Integer nullable(Node n){if(n==Node.EMPTY)return null;try{return Integer.valueOf(n.text());}catch(NumberFormatException e){return null;}}
    private static int integer(String s,int fallback){try{return Integer.parseInt(s);}catch(NumberFormatException e){return fallback;}}
    private static long timestamp(String s){try{return OffsetDateTime.parse(s).toInstant().toEpochMilli();}catch(java.time.format.DateTimeParseException e){return 0;}}
    private static boolean reliable(Node n){for(Node item:n.all()){String name=item.name.toLowerCase(Locale.ROOT),text=item.text().toLowerCase(Locale.ROOT);if(name.contains("fault")||name.contains("forecast")||name.equals("parkingstatusvalidity"))return false;if((name.contains("quality")||name.contains("computation")||name.contains("informationstatus"))&&!text.isEmpty()&&!Arrays.asList("real","reliable","accurate","measured").contains(text))return false;}return true;}
    private static double[] point(Node n){List<Node> points=n.descendants("pointCoordinates");if(points.size()!=1)return null;try{Node p=points.get(0);double lat=Double.parseDouble(p.child("latitude").text()),lon=Double.parseDouble(p.child("longitude").text());return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=50&&lat<=54&&lon>=3&&lon<=8?new double[]{lat,lon}:null;}catch(NumberFormatException e){return null;}}
    private static String value(Node n){List<Node> values=n.descendants("value");if(values.isEmpty())return "";for(Node v:values)if(v.attr("lang").equals("en"))return v.text();return values.get(0).text();}
    private static final class Node {
        static final Node EMPTY=new Node("");final String name;final Map<String,String> attributes=new HashMap<>();final List<Node> children=new ArrayList<>();final StringBuilder content=new StringBuilder();Node(String name){this.name=name;}
        String text(){return content.toString().trim();}String attr(String s){return attributes.getOrDefault(s,"");}
        Node child(String s){for(Node n:children)if(n.name.equals(s))return n;return EMPTY;}
        List<Node> children(String s){ArrayList<Node> result=new ArrayList<>();for(Node n:children)if(n.name.equals(s))result.add(n);return result;}
        List<Node> descendants(String s){ArrayList<Node> result=new ArrayList<>();for(Node n:children){if(n.name.equals(s))result.add(n);result.addAll(n.descendants(s));}return result;}
        List<Node> all(){ArrayList<Node> result=new ArrayList<>();result.add(this);for(Node n:children)result.addAll(n.all());return result;}
    }
    private static final class Document {String tableId="",tableVersion="",informationStatus="";long publication;final List<Node> records=new ArrayList<>();}
    private static Document parse(byte[] bytes,boolean dynamic)throws IOException{
        if(bytes.length==0||bytes.length>1_048_576)throw new IOException("Parking XML exceeds safe size");
        Document document=new Document();String namespace=dynamic?DYNAMIC:STATIC,recordName=dynamic?"parkingRecordStatus":"parkingRecord";
        try{
            SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);XMLReader reader=factory.newSAXParser().getXMLReader();
            for(String feature:new String[]{"http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities","http://apache.org/xml/features/nonvalidating/load-external-dtd"})try{reader.setFeature(feature,false);}catch(SAXNotRecognizedException|SAXNotSupportedException unsupported){/* startDTD and resolver below reject all declarations. */}
            DefaultHandler2 handler=new DefaultHandler2(){final ArrayDeque<Node> stack=new ArrayDeque<>();int depth,nodes,recordDepth;String header="";StringBuilder content=new StringBuilder();
                public void startDTD(String n,String p,String s)throws SAXException{throw new SAXException("DTD is forbidden");}
                public InputSource resolveEntity(String publicId,String systemId)throws SAXException{throw new SAXException("External entities are forbidden");}
                public void startElement(String uri,String local,String q,Attributes a)throws SAXException{
                    if(++depth>64||++nodes>30_000)throw new SAXException("Parking XML structure exceeds safe limits");
                    if((local.equals("parkingTable")||local.equals("parkingTableReference"))&&uri.equals(namespace)){document.tableId=a.getValue("id");document.tableVersion=a.getValue("version");}
                    if(stack.isEmpty()&&local.equals(recordName)&&uri.equals(namespace)){recordDepth=depth;Node record=new Node(local);copy(a,record);stack.push(record);}
                    else if(!stack.isEmpty()){if(!uri.equals(namespace))throw new SAXException("Unexpected namespace in parking record");Node child=new Node(local);copy(a,child);stack.peek().children.add(child);stack.push(child);}
                    else{header=local;content.setLength(0);}
                }
                void copy(Attributes a,Node node){for(int i=0;i<a.getLength();i++)node.attributes.put(a.getLocalName(i),a.getValue(i));}
                public void characters(char[] ch,int start,int length)throws SAXException{StringBuilder target=stack.isEmpty()?content:stack.peek().content;if(target.length()+length>4096)throw new SAXException("Parking XML text exceeds safe limit");target.append(ch,start,length);}
                public void endElement(String uri,String local,String q)throws SAXException{
                    if(!stack.isEmpty()){Node n=stack.pop();if(depth==recordDepth){document.records.add(n);if(document.records.size()>2000)throw new SAXException("Too many parking records");}}
                    else if(local.equals(header)){String text=content.toString().trim();if(local.equals("publicationTime"))document.publication=timestamp(text);if(local.equals("informationStatus"))document.informationStatus=text;}
                    depth--;
                }
                public void error(SAXParseException e)throws SAXException{throw e;}public void fatalError(SAXParseException e)throws SAXException{throw e;}
            };
            reader.setContentHandler(handler);reader.setEntityResolver(handler);reader.setErrorHandler(handler);reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
            if(document.tableId==null||document.tableVersion==null)throw new IOException("Missing parking table identity");return document;
        }catch(ParserConfigurationException|SAXException e){throw new IOException("Invalid or unsafe parking XML",e);}
    }
}
