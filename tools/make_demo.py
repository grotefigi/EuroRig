"""A fictional test network near Bucharest. Never use it as a real map."""
from pathlib import Path
from compile_map import write_pack

nodes = [(44.44, 26.04, 'West depot'), (44.44, 26.055, 'Low bridge'),
         (44.44, 26.07, 'City junction'), (44.44, 26.09, 'East logistics park'),
         (44.455, 26.04, 'North interchange'), (44.455, 26.065, 'Truck parking'),
         (44.455, 26.09, 'Ring road exit'), (44.425, 26.055, 'Fuel station'),
         (44.425, 26.08, 'South delivery hub'), (44.435, 26.10, 'Warehouse gate')]
edges = []
roads = [(0,1,'Bridge approach',3.5,0,0), (1,2,'Bridge approach',3.5,0,0),
         (2,3,'City shortcut',0,7.5,0), (0,4,'Depot connector',0,0,0),
         (4,5,'North freight ring',0,0,0), (5,6,'North freight ring',0,0,0),
         (6,3,'Logistics connector',0,0,0), (0,7,'South access',0,0,0),
         (7,8,'South toll road',0,18,4), (8,3,'Delivery avenue',0,0,0),
         (3,9,'Warehouse road',0,0,0)]
for way, (a,b,name,height,weight,flags) in enumerate(roads,1):
    for start,end in [(a,b),(b,a)]:
        edges.append((start,end,way,name,'primary',height,0,0,weight,0,flags,60))
data = {'name':'Bucharest training network', 'attribution':'Fictional EuroRig test data · CC0',
        'date':'Synthetic fixture v1 · Not real roads', 'demo':True, 'nodes':nodes,'edges':edges,'turns':[]}
write_pack(data, Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets/demo.europack')
print('Created fictional training map')
