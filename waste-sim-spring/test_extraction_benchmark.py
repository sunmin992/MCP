import unittest
import extraction_benchmark as b
import llm_benchmark as old

class ExtractionTests(unittest.TestCase):
    def setUp(self):
        _, self.fields = old.load_template()

    def test_invalid_values_remain_extractable(self):
        for field, value, span in [('numBuildings',99,'99개'),('engine','rust','rust'),('collectionTime','25:00','25:00')]:
            self.assertEqual([], b.extraction_errors({'values':[dict(field=field,value=value,span=span)]},self.fields))
        schema=b.extraction_schema(self.fields)
        for option in schema['properties']['values']['items']['anyOf']:
            self.assertNotIn('enum',option['properties']['value'])
            self.assertNotIn('maximum',option['properties']['value'])

    def test_types_unknown_fields_and_invented_values_fail(self):
        for row in [dict(field='days',value='30',span='30일'),dict(field='fake',value=30,span='30일')]:
            self.assertTrue(b.extraction_errors({'values':[row]},self.fields))
        c=dict(request='설정해줘',expected={})
        out={'values':[dict(field='days',value=30,span='설정해줘')]}
        self.assertFalse(b.metrics(c,out,self.fields)['extractionPass'])

    def test_followups_never_hide_missing_extractions(self):
        for c in b.cases():
            stated=set(c.get('expected',{}))|set(c.get('invalid',{}))
            self.assertFalse(stated & set(b.followups(c)))

    def test_short_prompt_and_schema_contract(self):
        t,_=old.load_template()
        self.assertLess(len(b.compact_prompt(self.fields)),len(old.prompt_from(t,self.fields)))
        self.assertEqual([],b.extraction_errors({'values':[]},self.fields))
        self.assertTrue(b.extraction_errors({},self.fields))
        self.assertTrue(b.extraction_errors({'values':[],'scenario':{}},self.fields))

if __name__=='__main__':unittest.main()
