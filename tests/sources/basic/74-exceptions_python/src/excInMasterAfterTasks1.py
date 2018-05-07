from pycompss.api.task import task

@task(returns=int)
def increment(v):
    return v+1

def main():
    from pycompss.api.api import compss_wait_on
    values = [0, 100, 200, 300]
    for i in range(4):
        for j in range(5):
            values[i] = increment(values[i])
    raise Exception('GENERAL EXCEPTION RAISED - HAPPENED AFTER SUBMITTING TASKS AT MASTER BUT BEFORE SYNC.')
    result = compss_wait_on(values)

    if result[0] == 5 and result[1] == 105 and result[2] == 205 and result[3] == 305:
        print("- Result value: OK")
    else:
        print("- Result value: ERROR")
        print("- This error is a root error. Please fix error at test 19.")


if __name__=='__main__':
    main()
