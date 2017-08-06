import os
import sys
import re


dir = sys.argv[1]
files = [string for string in os.listdir(dir) if string.endswith(".json") and string.startswith("part-")]

pattern = re.compile("\d+")

for fin in files:
    suffix = ""
    with open(dir + "/" + fin) as data_in: 
        for line in data_in:
            if suffix == "" :
                suffix = pattern.search(line).group()
                if len(suffix) == 1:
                    suffix = "0" + suffix
                fout = open(dir + "/" + suffix + ".json", "w+")
                fout.write("[")
                fout.write(line)
            else:
                fout.write(",")
                fout.write(line)

        if  suffix != "":
            fout.write("]")
            fout.close()
            suffix = ""
