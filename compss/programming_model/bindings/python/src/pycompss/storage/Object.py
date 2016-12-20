'''
Dummy SCO class
---------------

WARNING! Only for testing purposes.
         Considers the persitence within the /tmp folder of the localhost.
'''
import os
import uuid
from cPickle import dump

storage_path = '/tmp/'

class SCO(object):

    id = None
    alias = None

    def __init__(self):
        pass

    def __init__(self, alias):
        self.alias = alias

    def getID(self):
        return self.id

    def makePersistent(self):
        if self.id is None:
            uid = uuid.uuid4()
            self.id = str(uid)
            file_name = str(uid) + '.PSCO'
            file_path = storage_path + file_name

            # Serialize object and write to disk
            dump(self, open(file_path, "wb"))

    def delete(self):
        if self.id is None:
            # Remove file from /tmp
            file_name = str(self.id) + '.PSCO'
            file_path = storage_path + file_name
            try:
                os.remove(file_path)
            except:
                print "PSCO: " + file_path + " Does not exist!"
