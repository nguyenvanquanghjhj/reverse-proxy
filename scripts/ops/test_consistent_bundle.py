#!/usr/bin/env python3
"""Offline database+files snapshot tests with a fake pg_dump, never touching real SQL."""
import io
import json
from pathlib import Path
import os
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
import consistent_bundle as bundle

class PgFake:
    def __init__(self):self.restored=[]
    def load_env(self,file):
        return {key:val for key,val in (line.split('=',1) for line in file.read_text().splitlines() if '=' in line)}
    def connect_info(self,file):
        return {'host':'127.0.0.1','port':'55434','database':'postgres','user':'qa','password':'QA_NOT_SECRET'}
    def backup(self,con,out):
        dump=out/'pbl4-test.dump';dump.write_bytes(b'PGDMP fake fixture, NOT PostgreSQL archive')
        manifest={'filename':dump.name,'sha256':bundle.sha_file(dump),'size_bytes':dump.stat().st_size}
        (out/'pbl4-test.json').write_text(json.dumps(manifest))
    def validated_archive(self,manifest,con):
        meta=json.loads(manifest.read_text());path=manifest.parent/meta['filename']
        if bundle.sha_file(path)!=meta['sha256']:raise ValueError('dump checksum')
        return path,meta
    def restore(self,con,manifest,target,ack):
        if ack!='CREATE_NEW_DATABASE_ONLY':raise ValueError('ack')
        self.restored.append(target)

class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.files=self.root/'shared-files';(self.files/'store').mkdir(parents=True)
        (self.files/'store/alpha.bin').write_bytes(b'First verified PDF or photograph')
        (self.files/'store/beta.bin').write_bytes(b'Another saved attachment')
        self.env=self.root/'.env.store'
        self.env.write_text(f'SHOP_FILES_DIR={self.files}\nSHOP_DB_PASSWORD=SUPER_SECRET_STRING\n')
        self.pg=PgFake()
        self.patcher=patch.object(bundle,'pg_module',return_value=self.pg)
        self.portpatch=patch.object(bundle,'probe_writer_ports',return_value=[])
        self.patcher.start();self.portpatch.start()
        self.addCleanup(self.patcher.stop);self.addCleanup(self.portpatch.stop)

    def make(self):
        path=self.root/'backup.tar.gz'
        summary=bundle.backup_offline(self.env,path,'STOPPED_ALL_WRITERS')
        self.assertEqual(summary['files'],2)
        self.assertNotIn('SUPER_SECRET_STRING',str(summary))
        return path

    def test_coherent_archive_verify_and_restore_to_new(self):
        path=self.make()
        metadata=bundle.verify(path)
        self.assertEqual(len(metadata['files']),2)
        with tarfile.open(path,'r:gz') as archive:
            self.assertNotIn('SUPER_SECRET_STRING',archive.extractfile('manifest.json').read().decode())
        dest=self.root/'restored-files'
        receipt=bundle.restore_new(self.env,path,'restored_database',dest,'CREATE_NEW_DATABASE_AND_FILES')
        self.assertEqual(self.pg.restored,['restored_database'])
        self.assertEqual(receipt['file_count'],2)
        self.assertEqual((dest/'store/alpha.bin').read_bytes(),b'First verified PDF or photograph')
        with self.assertRaises(ValueError):
            bundle.restore_new(self.env,path,'restored_database',dest,'CREATE_NEW_DATABASE_AND_FILES')

    def test_refuse_backup_without_ack(self):
        with self.assertRaises(ValueError):bundle.backup_offline(self.env,self.root/'out.tar.gz','')
        self.assertFalse((self.root/'out.tar.gz').exists())

    def test_refuse_when_backend_ports_open(self):
        with patch.object(bundle,'probe_writer_ports',return_value=[8080]):
            with self.assertRaises(RuntimeError):bundle.backup_offline(self.env,self.root/'x.tar.gz','STOPPED_ALL_WRITERS')

    def test_refuse_changed_archive(self):
        path=self.make()
        raw=bytearray(path.read_bytes());raw[len(raw)//2]^=0x80;path.write_bytes(raw)
        with self.assertRaises((ValueError,tarfile.TarError,OSError,EOFError)):
            bundle.verify(path)

    def test_refuse_symlink(self):
        link = self.files/'store/malicious.bin'
        if os.name == 'nt':
            # A real Windows junction needs no symlink privilege and must also be refused.
            subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(self.root)],
                           check=True, capture_output=True)
            self.assertTrue(link.is_junction())
        else:
            link.symlink_to(self.env)
        with self.assertRaises(ValueError):bundle.source_tree(self.files)
        with self.assertRaises(ValueError):bundle.no_symlink_ancestors(link)

    def test_refuse_tar_path_traversal(self):
        path=self.root/'bad.tar.gz'
        with tarfile.open(path,'w:gz') as tar:
            for name in ['manifest.json','../outside']:
                b=b'{}';member=tarfile.TarInfo(name);member.size=len(b);tar.addfile(member,io.BytesIO(b))
        with self.assertRaises(ValueError):bundle.verify(path)
        self.assertFalse((self.root/'outside').exists())

if __name__=='__main__':unittest.main(verbosity=2)
