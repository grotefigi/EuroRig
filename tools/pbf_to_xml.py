"""Convert a small OSM PBF for EuroPack display compilation (not Valhalla tiles).

Optional build dependency: osmium==4.3.1. Geometry is streamed to XML; complete
road ways and restriction relations still need to be present in the input.
"""
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


def convert(source, output):
    import osmium
    if Path(source).stat().st_size>50*1024*1024:
        raise ValueError('This display compiler is limited to small extracts (50 MiB input)')
    if Path(source).resolve()==Path(output).resolve():raise ValueError('Output cannot replace input')
    with Path(output).open('wb') as stream:
        stream.write(b'<?xml version="1.0" encoding="UTF-8"?><osm version="0.6">')
        class Handler(osmium.SimpleHandler):
            def emit(self, element, tags):
                for tag in tags:ET.SubElement(element,'tag',k=tag.k,v=tag.v)
                stream.write(ET.tostring(element,encoding='utf-8'))
            def node(self,node):
                self.emit(ET.Element('node',id=str(node.id),lat=str(node.location.lat),lon=str(node.location.lon)),node.tags)
            def way(self,way):
                if not way.tags.get('highway') and way.tags.get('route')!='ferry':return
                e=ET.Element('way',id=str(way.id))
                for node in way.nodes:ET.SubElement(e,'nd',ref=str(node.ref))
                self.emit(e,way.tags)
            def relation(self,relation):
                if not relation.tags.get('type','').startswith('restriction'):return
                e=ET.Element('relation',id=str(relation.id))
                for member in relation.members:
                    ET.SubElement(e,'member',type={'n':'node','w':'way','r':'relation'}[member.type],ref=str(member.ref),role=member.role)
                self.emit(e,relation.tags)
        Handler().apply_file(str(source))
        stream.write(b'</osm>')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source');parser.add_argument('output')
    args=parser.parse_args();convert(args.source,args.output)
