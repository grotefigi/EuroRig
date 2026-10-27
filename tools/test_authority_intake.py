import copy
import csv
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from authority_intake import FIELDS, intake
from compile_display import compile_display
from enrich_display import enrich
from package_region import sha256


class AuthorityIntakeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.display = self.root/'display.sqlite'
        with closing(sqlite3.connect(self.display)) as db:
            db.executescript('PRAGMA user_version=1;CREATE TABLE roads(id INTEGER PRIMARY KEY);INSERT INTO roads VALUES(101),(102);')
        self.source, self.review_file, self.match_file = [self.root/name for name in ('source.csv','review.json','matches.json')]
        self.rows = [dict(zip(FIELDS, ('bridge-1','RO','height','350','cm','both','','2026-01-01','','','')))]
        self.matches = {'bridge-1': {'osm_way_ids': [101], 'review_ref': 'Synthetic exact-way correspondence fixture'}}
        self.review = {'schema_version': 1, 'status': 'approved', 'source_id': 'synthetic-ro',
                       'publisher': 'EuroRig original synthetic test source', 'source_url': 'https://example.org/synthetic',
                       'country': 'RO', 'license': 'Original EuroRig fixture', 'reuse_allowed': True,
                       'license_review': 'Original fixture, not third-party material',
                       'currentness_review': 'Synthetic dates for a controlled intake test',
                       'reviewed_on': '2026-10-01', 'review_due': '2026-11-01'}
        self.save()

    def save(self):
        with self.source.open('w',encoding='utf-8',newline='') as f:
            writer=csv.DictWriter(f,fieldnames=FIELDS);writer.writeheader();writer.writerows(self.rows)
        self.match_file.write_text(json.dumps(self.matches),encoding='utf-8')
        self.review.update(source_sha256=sha256(self.source), matches_sha256=sha256(self.match_file), display_sha256=sha256(self.display))
        self.review_file.write_text(json.dumps(self.review),encoding='utf-8')

    def run_intake(self, name='output', as_of='2026-10-25'):
        return intake(self.source,self.review_file,self.match_file,self.display,self.root/name,as_of)

    def reasons(self, result):
        self.assertEqual(0,result['accepted'])
        return result['rejection_counts']

    def test_normalizes_units_preserves_raw_and_is_reproducible_without_input_changes(self):
        before={p.name:sha256(p) for p in (self.source,self.review_file,self.match_file,self.display)}
        result=self.run_intake()
        self.assertEqual((1,1,0),(result['records'],result['accepted'],result['rejected']))
        record=json.loads((self.root/'output/accepted.jsonl').read_text())
        self.assertEqual(3.5,record['value_si']);self.assertEqual({'maxheight':'3.5'},record['tags'])
        self.assertEqual('350',record['raw']['value']);self.assertEqual('cm',record['raw']['unit'])
        self.assertEqual([101],record['osm_way_ids']);self.assertEqual(1,record['kind'])
        self.assertEqual('unknown',result['legal_completeness']);self.assertFalse(result['map_modified'])
        self.run_intake('repeat')
        for name in ('manifest.json','accepted.jsonl','rejected.jsonl','review.json','source.csv','matches.json'):
            self.assertEqual((self.root/'output'/name).read_bytes(),(self.root/'repeat'/name).read_bytes())
        self.assertEqual(before,{p.name:sha256(p) for p in (self.source,self.review_file,self.match_file,self.display)})
        self.assertEqual(result['records'],result['accepted']+result['rejected'])

    def test_same_adapter_handles_a_second_country_and_loaded_mass_basis(self):
        self.review.update(country='MD',source_id='synthetic-md')
        self.rows[0].update(country='MD',restriction='loaded_mass',value='7500',unit='kg',mass_basis='loaded',direction='backward')
        self.save();result=self.run_intake()
        self.assertEqual(1,result['accepted'])
        record=json.loads((self.root/'output/accepted.jsonl').read_text())
        self.assertEqual({'maxweight:backward':'7.5'},record['tags']);self.assertEqual(33,record['kind'])

    def test_unreviewed_unknown_rights_and_stale_review_are_quarantined(self):
        cases=[('status','draft','source_not_approved'),('reuse_allowed',False,'reuse_not_approved'),
               ('license','unknown','missing_license'),('review_due','2026-10-20','review_not_current'),
               ('reviewed_on','2026-10-26','review_not_current'),('currentness_review','','missing_currentness_review')]
        original=copy.deepcopy(self.review)
        for index,(key,value,reason) in enumerate(cases):
            with self.subTest(key=key,value=value):
                self.review={**original,key:value};self.save()
                self.assertIn(reason,self.reasons(self.run_intake('reject'+str(index))))

    def test_changed_source_map_or_match_snapshots_cannot_reuse_approval(self):
        for index,key in enumerate(('source_sha256','display_sha256','matches_sha256')):
            with self.subTest(key=key):
                self.save();wrong={**self.review,key:'0'*64}
                self.review_file.write_text(json.dumps(wrong),encoding='utf-8')
                self.assertIn(key+'_mismatch',self.reasons(self.run_intake('changed'+str(index))))

    def test_scope_missing_match_and_missing_road_are_rejected(self):
        self.rows[0]['country']='MD';self.save()
        self.assertIn('country_not_in_reviewed_scope',self.reasons(self.run_intake('scope')))
        self.rows[0]['country']='RO';self.matches={};self.save()
        self.assertIn('road_match_not_reviewed',self.reasons(self.run_intake('unmatched')))
        self.matches={'bridge-1':{'osm_way_ids':[999],'review_ref':'synthetic'}};self.save()
        self.assertIn('matched_road_missing_from_snapshot',self.reasons(self.run_intake('missing')))

    def test_ambiguous_and_noninteger_way_ids_are_rejected(self):
        for index,ways in enumerate(([101,101],[True],['101'],[],[0],[2**63])):
            with self.subTest(ways=ways):
                self.matches['bridge-1']['osm_way_ids']=ways;self.save()
                self.assertIn('invalid_or_ambiguous_road_ids',self.reasons(self.run_intake('ids'+str(index))))

    def test_unknown_mass_basis_conditions_and_semantics_are_not_guessed(self):
        cases=[dict(restriction='loaded_mass',mass_basis='permitted',unit='t',value='7.5'),
               dict(condition='Mo-Fr 08:00-10:00'),dict(exception='local deliveries'),
               dict(restriction='adr'),dict(direction='unknown'),dict(unit='kg')]
        original=copy.deepcopy(self.rows[0])
        for index,changes in enumerate(cases):
            self.rows[0]={**original,**changes};self.save()
            result=self.run_intake('semantics'+str(index))
            self.assertEqual(1,result['rejected']);self.assertEqual(0,result['accepted'])

    def test_invalid_numeric_and_expired_or_future_records_are_rejected(self):
        original=copy.deepcopy(self.rows[0])
        cases=[dict(value=v) for v in ('0','-1','NaN','Infinity','1e9')]
        cases.extend((dict(valid_from=''),dict(valid_until='2026-10-24'),dict(valid_from='2026-10-26'),dict(valid_until='2025-01-01')))
        for index,changes in enumerate(cases):
            self.rows[0]={**original,**changes};self.save()
            self.assertEqual(1,self.run_intake('invalid'+str(index))['rejected'])

    def test_delivery_access_stays_a_restriction_without_granting_permission(self):
        self.rows[0].update(restriction='hgv_access',value='delivery',unit='');self.save()
        self.assertEqual(1,self.run_intake()['accepted'])
        record=json.loads((self.root/'output/accepted.jsonl').read_text())
        self.assertEqual({'hgv':'delivery'},record['tags']);self.assertEqual(2,record['kind'])
        self.assertNotIn('permission',record)

    def test_duplicate_record_ids_are_all_quarantined(self):
        self.rows.append(dict(self.rows[0]));self.save()
        result=self.run_intake()
        self.assertEqual((0,2),(result['accepted'],result['rejected']))
        self.assertEqual(2,result['rejection_counts']['duplicate_record_id'])

    def test_conflicting_overlapping_values_reject_both_but_opposite_directions_survive(self):
        self.rows[0]['direction']='forward'
        self.rows.append({**self.rows[0],'record_id':'bridge-2','value':'450'})
        self.matches['bridge-2']={'osm_way_ids':[101],'review_ref':'synthetic'};self.save()
        result=self.run_intake('conflict')
        self.assertEqual(2,result['rejection_counts']['conflicting_overlapping_records'])
        self.rows[1]['direction']='backward';self.save()
        self.assertEqual(2,self.run_intake('opposite')['accepted'])
        self.rows[0]['direction']='both';self.save()
        self.assertEqual(2,self.run_intake('overlap')['rejected'])

    def test_existing_outputs_are_preserved(self):
        self.run_intake();before=(self.root/'output/manifest.json').read_bytes()
        with self.assertRaises(FileExistsError):self.run_intake()
        self.assertEqual(before,(self.root/'output/manifest.json').read_bytes())

    def test_duplicate_json_keys_and_csv_headers_fail_before_output(self):
        self.review_file.write_text('{"status":"draft","status":"approved"}',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'Duplicate JSON key'):self.run_intake()
        self.review_file.write_text('{"value":NaN}',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'Nonstandard JSON'):self.run_intake()
        self.save();self.source.write_text('record_id,record_id\nx,y\n',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'headers'):self.run_intake()
        self.assertFalse((self.root/'output').exists())

    def test_empty_sources_and_active_sqlite_sidecars_are_not_accepted(self):
        self.rows=[];self.save()
        with self.assertRaisesRegex(ValueError,'no records'):self.run_intake()
        sidecar=Path(str(self.display)+'-wal');sidecar.write_bytes(b'active')
        with self.assertRaisesRegex(ValueError,'finalized'):self.run_intake()
        sidecar.unlink()
        with closing(sqlite3.connect(self.display)) as db:
            db.execute('PRAGMA user_version=2')
        self.save()
        with self.assertRaisesRegex(ValueError,'Unsupported display'):self.run_intake()

    def test_multiline_csv_fields_are_preserved(self):
        self.rows[0]['exception']='first line, with comma\nsecond "quoted" line';self.save()
        result=self.run_intake();self.assertEqual(1,result['records'])
        record=json.loads((self.root/'output/rejected.jsonl').read_text())
        self.assertEqual(self.rows[0]['exception'],record['raw']['exception'])

    def test_extra_and_missing_csv_fields_are_quarantined_without_losing_the_batch(self):
        original=self.source.read_text(encoding='utf-8')
        malformed=[original.rstrip('\n')+',unexpected\n',original.splitlines()[0]+'\nonly-one-field\n']
        for index,text in enumerate(malformed):
            self.source.write_text(text,encoding='utf-8')
            self.review['source_sha256']=sha256(self.source)
            self.review_file.write_text(json.dumps(self.review),encoding='utf-8')
            result=self.run_intake('malformed'+str(index))
            self.assertEqual((1,0,1),(result['records'],result['accepted'],result['rejected']))
            self.assertIn('malformed_csv_record',result['rejection_counts'])

    def test_cli_works_with_a_real_compiled_and_enriched_map(self):
        self.display=self.root/'compiled.sqlite'
        fixture=Path(__file__).parent/'fixtures/profiles.osm'
        compile_display([fixture],self.display,'Synthetic intake');enrich([fixture],self.display)
        self.rows[0]['value']='425'
        self.matches['bridge-1']['osm_way_ids']=[20000003];self.save()
        original_display=sha256(self.display)
        command=[sys.executable,str(Path(__file__).with_name('authority_intake.py')),
                 '--source',str(self.source),'--review',str(self.review_file),'--matches',str(self.match_file),
                 '--display',str(self.display),'--out',str(self.root/'cli'),'--as-of','2026-10-25']
        result=subprocess.run(command,capture_output=True,text=True,check=True)
        self.assertEqual(1,json.loads(result.stdout)['accepted'])
        self.assertEqual(4.25,json.loads((self.root/'cli/accepted.jsonl').read_text())['value_si'])
        self.assertEqual(original_display,sha256(self.display))
        with closing(sqlite3.connect(self.display)) as db:
            self.assertEqual(3.5,db.execute('SELECT height FROM road_rules WHERE way=20000003').fetchone()[0])


if __name__=='__main__':unittest.main()
